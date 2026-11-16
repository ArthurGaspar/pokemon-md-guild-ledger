package dev.arthurgaspar.expeditions.platform.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.arthurgaspar.expeditions.platform.Role;

/** Event payloads and their type names. See docs/design.md, "Event contracts". */
public final class Events {

    private Events() {
    }

    public static final String EXPEDITION_PUBLISHED = "expedition.published";
    public static final String CAPACITY_CHANGED = "capacity.changed";
    public static final String RESERVATION_HELD = "reservation.held";
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";
    public static final String RESERVATION_RELEASED = "reservation.released";
    public static final String PARTY_ASSEMBLED = "party.assembled";
    public static final String PARTY_REVERTED = "party.reverted";
    public static final String PARTY_LOCKED = "party.locked";
    public static final String PARTY_RELEASED = "party.released";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String CERTIFICATE_ISSUED = "certificate.issued";

    /** Why a run failed. They compensate differently: see docs/design.md, "Phase 2". */
    public enum RunFailure {
        /** The dungeon beat the party. The expedition happened; the slots stay used. */
        FAINTED,
        /** The run never got through the simulator before its deadline. The party is released and the slots reopen. */
        UNAVAILABLE
    }

    public record ExpeditionPublished(long id, String code, String name, String dungeon, String difficulty,
                                      Instant opensAt, int parties, Map<Role, Integer> composition) {}

    public record CapacityChanged(long expeditionId, int openSlots, long version) {}

    public record ReservationHeld(long reservationId, long expeditionId, long partyId, String adventurerId,
                                  Role role, Instant heldUntil) {}

    public record ReservationConfirmed(long reservationId, long expeditionId, long partyId, String adventurerId) {}

    public record ReservationReleased(long reservationId, long expeditionId, long partyId, String adventurerId,
                                      Role role, String reason) {}

    public record Member(long reservationId, String adventurerId, Role role) {}

    public record PartyAssembled(long expeditionId, long partyId, Instant readyBy, List<Member> members) {}

    public record PartyReverted(long expeditionId, long partyId, List<String> failed, List<String> kept) {}

    public record PartyLocked(long expeditionId, long partyId, List<Member> members) {}

    public record PartyReleased(long expeditionId, long partyId, List<String> released, String reason) {}

    public record RunCompleted(String runId, long expeditionId, long partyId, List<Member> members, int floorsCleared,
                               int pokeFound) {}

    public record RunFailed(String runId, long expeditionId, long partyId, List<Member> members, RunFailure reason,
                            String detail) {}

    /** One per member of a cleared run; what the ledger would consume to pay the share into a team. */
    public record CertificateIssued(long certificateId, String runId, long expeditionId, String adventurerId, Role role,
                                    int pokeShare) {}
}
