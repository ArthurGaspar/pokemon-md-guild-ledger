package dev.arthurgaspar.expeditions.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.Admission;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.InvalidAdmission;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.CapacityChanged;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyAssembled;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyReleased;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyReverted;
import dev.arthurgaspar.expeditions.platform.events.Events.ReservationConfirmed;
import dev.arthurgaspar.expeditions.platform.events.Events.ReservationHeld;
import dev.arthurgaspar.expeditions.platform.events.Events.ReservationReleased;
import dev.arthurgaspar.expeditions.platform.events.Topics;
import dev.arthurgaspar.expeditions.platform.outbox.Outbox;
import dev.arthurgaspar.expeditions.platform.web.ApiException;
import dev.arthurgaspar.expeditions.reservation.Reservation.Status;

/**
 * Holds, the ready check and compensation. Every method that changes an expedition's parties starts by locking
 * that expedition's inventory row, and records its events in the same transaction (see docs/design.md).
 */
@Service
public class ReservationService {

    public record MemberView(String adventurerId, Role role, Status status) {}

    public record PartyView(long id, int number, Party.Status status, Instant readyBy, List<MemberView> members) {}

    public record View(long id, long expeditionId, Role role, Status status, Instant heldUntil, PartyView party) {}

    public record Capacity(long expeditionId, int openSlots, long version, Map<Party.Status, Long> parties) {}

    private record Locked(ExpeditionInventory inventory, Reservation reservation) {}

    private final InventoryRepository inventories;
    private final PartyRepository parties;
    private final ReservationRepository reservations;
    private final AdmissionTokens admissions;
    private final ReservationSettings settings;
    private final Outbox outbox;
    private final Clock clock;

    public ReservationService(InventoryRepository inventories, PartyRepository parties, ReservationRepository reservations,
                              AdmissionTokens admissions, ReservationSettings settings, Outbox outbox, Clock clock) {
        this.inventories = inventories;
        this.parties = parties;
        this.reservations = reservations;
        this.admissions = admissions;
        this.settings = settings;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** From {@code expedition.published}. Delivered at least once, so a known expedition is skipped. */
    @Transactional
    public void register(ExpeditionPublished e) {
        if (inventories.existsById(e.id())) {
            return;
        }
        ExpeditionInventory inventory = inventories.save(
                new ExpeditionInventory(e.id(), e.code(), e.opensAt(), e.parties(), e.composition()));
        for (int n = 1; n <= e.parties(); n++) {
            parties.save(new Party(e.id(), n));
        }
        capacityChanged(inventory);
    }

    @Transactional
    public View hold(String adventurerId, long expeditionId, Role role, String admissionToken) {
        Instant now = clock.instant();
        Admission admission;
        try {
            admission = admissions.verify(admissionToken, now);
        } catch (InvalidAdmission e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admission.invalid", e.getMessage());
        }
        if (admission.expeditionId() != expeditionId || !admission.adventurerId().equals(adventurerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admission.invalid", "This admission is for someone else");
        }
        ExpeditionInventory inventory = lock(expeditionId);
        if (!inventory.isOpenAt(now)) {
            throw ApiException.conflict("expedition.not-open", "This expedition opens at " + inventory.getOpensAt());
        }
        if (reservations.hasActive(expeditionId, adventurerId)) {
            throw ApiException.conflict("reservation.exists", "You already have a place in this expedition");
        }
        Map<Role, Integer> composition = inventory.composition();
        // the fullest party with this role open, so parties complete instead of all sitting three-quarters full
        Party party = parties.findByExpeditionIdAndStatusOrderByNumber(expeditionId, Party.Status.FORMING).stream()
                .filter(p -> p.openFor(role, composition) > 0)
                .max(Comparator.comparingInt(Party::filled).thenComparing(Party::getNumber, Comparator.reverseOrder()))
                .orElseThrow(() -> inventory.getOpenSlots() == 0
                        ? ApiException.conflict("expedition.sold-out", "Every slot is taken")
                        : ApiException.conflict("reservation.role-full", "No " + role + " slot is open right now"));

        Reservation r = reservations.save(party.join(adventurerId, role, composition, now, now.plus(settings.holdTtl())));
        inventory.takeSlot();
        capacityChanged(inventory);
        emit(expeditionId, Events.RESERVATION_HELD, new ReservationHeld(r.getId(), expeditionId, party.getId(),
                adventurerId, role, r.getHeldUntil()));
        if (party.isFull(composition)) {
            party.startReadyCheck(now.plus(settings.readyCheck()));
            emit(expeditionId, Events.PARTY_ASSEMBLED,
                    new PartyAssembled(expeditionId, party.getId(), party.getReadyBy(), members(party)));
        }
        return view(r);
    }

    @Transactional
    public View confirm(String adventurerId, long reservationId) {
        Reservation r = lockOwn(adventurerId, reservationId).reservation();
        Party party = r.getParty();
        if (r.getStatus() == Status.CONFIRMED || r.getStatus() == Status.LOCKED) {
            return view(r);
        }
        if (r.getStatus() != Status.HELD) {
            throw ApiException.conflict("reservation.closed", "This reservation is " + r.getStatus().name().toLowerCase());
        }
        if (party.getStatus() != Party.Status.ASSEMBLED) {
            throw ApiException.conflict("reservation.not-in-ready-check", "Your party is still forming");
        }
        if (party.readyCheckMissed(clock.instant())) {
            throw ApiException.conflict("ready-check.missed", "The ready check is over");
        }
        r.confirm();
        emit(r.getExpeditionId(), Events.RESERVATION_CONFIRMED,
                new ReservationConfirmed(r.getId(), r.getExpeditionId(), party.getId(), adventurerId));
        if (party.everyoneConfirmed()) {
            party.lock(clock.instant());
            emit(r.getExpeditionId(), Events.PARTY_LOCKED, new PartyLocked(r.getExpeditionId(), party.getId(), members(party)));
        }
        return view(r);
    }

    /** Leaving a forming party frees the slot; leaving during the ready check counts as declining it. */
    @Transactional
    public View cancel(String adventurerId, long reservationId) {
        Locked locked = lockOwn(adventurerId, reservationId);
        Reservation r = locked.reservation();
        if (r.getStatus().setOut()) {
            throw ApiException.conflict("reservation.locked", "Your party has already set out");
        }
        if (!r.getStatus().active()) {
            return view(r);
        }
        Party party = r.getParty();
        if (party.getStatus() == Party.Status.ASSEMBLED) {
            failReadyCheck(locked.inventory(), party, List.of(r), Status.DECLINED);
        } else {
            close(r, Status.CANCELLED);
            locked.inventory().returnSlots(1);
            capacityChanged(locked.inventory());
        }
        return view(r);
    }

    /** From {@code run.completed}, or {@code run.failed} with {@code FAINTED}. Repeats find the party already finished. */
    @Transactional
    public void runFinished(long expeditionId, long partyId, boolean cleared) {
        lock(expeditionId);
        Party party = parties.findWithMembers(partyId).orElseThrow();
        if (party.getStatus() == Party.Status.LOCKED) {
            party.finish(cleared, clock.instant());
        }
    }

    /**
     * From {@code run.failed} with {@code UNAVAILABLE}: the compensation for a run that never got through. Every
     * member's reservation is aborted, the slots go back to the queue, and the party starts forming again.
     */
    @Transactional
    public void runAborted(long expeditionId, long partyId, String detail) {
        ExpeditionInventory inventory = lock(expeditionId);
        Party party = parties.findWithMembers(partyId).orElseThrow();
        if (party.getStatus() != Party.Status.LOCKED) {
            return;
        }
        List<Reservation> released = party.release(clock.instant());
        released.forEach(r -> released(r, Status.ABORTED));
        inventory.returnSlots(released.size());
        capacityChanged(inventory);
        emit(expeditionId, Events.PARTY_RELEASED, new PartyReleased(expeditionId, partyId,
                released.stream().map(Reservation::getAdventurerId).toList(), detail));
    }

    /**
     * Expires lapsed holds in forming parties and fails ready checks past their deadline, for one expedition.
     * {@link Sweeper} calls it once a second for each expedition with something due.
     */
    @Transactional
    public void sweep(long expeditionId) {
        Instant now = clock.instant();
        ExpeditionInventory inventory = lock(expeditionId);
        List<Reservation> lapsed = reservations.lapsedHolds(expeditionId, now);
        lapsed.forEach(r -> close(r, Status.EXPIRED));
        if (!lapsed.isEmpty()) {
            inventory.returnSlots(lapsed.size());
            capacityChanged(inventory);
        }
        for (Party party : parties.missedReadyChecks(expeditionId, now)) {
            List<Reservation> unconfirmed = party.members().stream().filter(r -> r.getStatus() == Status.HELD).toList();
            failReadyCheck(inventory, party, unconfirmed, Status.EXPIRED);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> expeditionsWithWorkDue() {
        Instant now = clock.instant();
        return java.util.stream.Stream.concat(reservations.expeditionsWithLapsedHolds(now).stream(),
                parties.expeditionsWithMissedReadyChecks(now).stream()).distinct().toList();
    }

    @Transactional(readOnly = true)
    public View get(String adventurerId, long reservationId) {
        return reservations.findById(reservationId).filter(r -> r.getAdventurerId().equals(adventurerId))
                .map(this::view)
                .orElseThrow(() -> ApiException.notFound("reservation.not-found", "Reservation not found"));
    }

    @Transactional(readOnly = true)
    public List<View> mine(String adventurerId) {
        return reservations.findActiveFor(adventurerId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Capacity capacity(long expeditionId) {
        ExpeditionInventory inventory = inventories.findById(expeditionId)
                .orElseThrow(() -> ApiException.notFound("expedition.not-found", "Expedition not found"));
        Map<Party.Status, Long> counts = new EnumMap<>(Party.Status.class);
        for (Party.Status s : Party.Status.values()) {
            counts.put(s, 0L);
        }
        for (Object[] row : parties.countByStatus(expeditionId)) {
            counts.put((Party.Status) row[0], (Long) row[1]);
        }
        return new Capacity(expeditionId, inventory.getOpenSlots(), inventory.getCapacityVersion(), counts);
    }

    /**
     * The compensation: the members who failed the check lose their reservation, everyone else goes back to holding
     * a slot, and the party takes new members again.
     */
    private void failReadyCheck(ExpeditionInventory inventory, Party party, List<Reservation> failed, Status outcome) {
        failed.forEach(r -> close(r, outcome));
        List<Reservation> kept = party.revert(clock.instant().plus(settings.holdTtl()));
        inventory.returnSlots(failed.size());
        capacityChanged(inventory);
        emit(party.getExpeditionId(), Events.PARTY_REVERTED, new PartyReverted(party.getExpeditionId(), party.getId(),
                failed.stream().map(Reservation::getAdventurerId).toList(),
                kept.stream().map(Reservation::getAdventurerId).toList()));
    }

    private void close(Reservation r, Status outcome) {
        r.close(outcome, clock.instant());
        released(r, outcome);
    }

    private void released(Reservation r, Status outcome) {
        emit(r.getExpeditionId(), Events.RESERVATION_RELEASED, new ReservationReleased(r.getId(), r.getExpeditionId(),
                r.getParty().getId(), r.getAdventurerId(), r.getRole(), outcome.name()));
    }

    private ExpeditionInventory lock(long expeditionId) {
        return inventories.lockById(expeditionId)
                .orElseThrow(() -> ApiException.notFound("expedition.not-found", "Expedition not found"));
    }

    /** Locks first and reads after, so the reservation and its party are read under the lock. */
    private Locked lockOwn(String adventurerId, long reservationId) {
        ApiException notFound = ApiException.notFound("reservation.not-found", "Reservation not found");
        long expeditionId = reservations.findExpeditionId(reservationId).orElseThrow(() -> notFound);
        ExpeditionInventory inventory = lock(expeditionId);
        Reservation r = reservations.findById(reservationId)
                .filter(x -> x.getAdventurerId().equals(adventurerId))
                .orElseThrow(() -> notFound);
        return new Locked(inventory, r);
    }

    private void capacityChanged(ExpeditionInventory inventory) {
        emit(inventory.getId(), Events.CAPACITY_CHANGED,
                new CapacityChanged(inventory.getId(), inventory.getOpenSlots(), inventory.getCapacityVersion()));
    }

    private void emit(long expeditionId, String type, Object payload) {
        outbox.append(Topics.RESERVATIONS, Long.toString(expeditionId), type, payload);
    }

    private static List<Member> members(Party party) {
        return party.members().stream().map(r -> new Member(r.getId(), r.getAdventurerId(), r.getRole())).toList();
    }

    private View view(Reservation r) {
        Party p = r.getParty();
        List<MemberView> members = p.members().stream()
                .map(m -> new MemberView(m.getAdventurerId(), m.getRole(), m.getStatus())).toList();
        return new View(r.getId(), r.getExpeditionId(), r.getRole(), r.getStatus(), r.getHeldUntil(),
                new PartyView(p.getId(), p.getNumber(), p.getStatus(), p.getReadyBy(), members));
    }
}
