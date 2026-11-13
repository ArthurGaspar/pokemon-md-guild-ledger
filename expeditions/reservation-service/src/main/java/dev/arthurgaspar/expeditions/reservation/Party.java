package dev.arthurgaspar.expeditions.reservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.arthurgaspar.expeditions.platform.Role;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The saga's unit: {@code FORMING → ASSEMBLED → LOCKED}, and back to {@code FORMING} when the ready check fails.
 * The rules live here, free of Spring, so they can be tested directly; {@link ReservationService} supplies the lock,
 * the clock and the events.
 */
@Entity
@Table(name = "parties")
public class Party {

    public enum Status { FORMING, ASSEMBLED, LOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @Column(nullable = false)
    private int number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "ready_by")
    private Instant readyBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @OneToMany(mappedBy = "party", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Reservation> reservations = new ArrayList<>();

    protected Party() {
    }

    public Party(long expeditionId, int number) {
        this.expeditionId = expeditionId;
        this.number = number;
        this.status = Status.FORMING;
    }

    /** Reservations holding a slot, oldest first. */
    public List<Reservation> members() {
        return reservations.stream().filter(r -> r.getStatus().active()).toList();
    }

    public int filled() {
        return members().size();
    }

    public int openFor(Role role, Map<Role, Integer> composition) {
        long taken = members().stream().filter(r -> r.getRole() == role).count();
        return composition.getOrDefault(role, 0) - (int) taken;
    }

    public boolean isFull(Map<Role, Integer> composition) {
        return filled() == composition.values().stream().mapToInt(Integer::intValue).sum();
    }

    Reservation join(String adventurerId, Role role, Map<Role, Integer> composition, Instant now, Instant heldUntil) {
        if (status != Status.FORMING || openFor(role, composition) <= 0) {
            throw new IllegalStateException("Party " + id + " has no open " + role + " slot");
        }
        Reservation r = new Reservation(this, adventurerId, role, now, heldUntil);
        reservations.add(r);
        return r;
    }

    void startReadyCheck(Instant readyBy) {
        require(Status.FORMING);
        this.status = Status.ASSEMBLED;
        this.readyBy = readyBy;
    }

    public boolean everyoneConfirmed() {
        return members().stream().allMatch(r -> r.getStatus() == Reservation.Status.CONFIRMED);
    }

    void lock(Instant now) {
        require(Status.ASSEMBLED);
        if (!everyoneConfirmed()) {
            throw new IllegalStateException("Party " + id + " still has unconfirmed members");
        }
        members().forEach(Reservation::lock);
        this.status = Status.LOCKED;
        this.readyBy = null;
        this.lockedAt = now;
    }

    /**
     * Undoes a formation after the ready check failed. The caller has already closed the members who failed it;
     * everyone left keeps their slot with a fresh hold, and the party takes new members again.
     *
     * @return the members who kept their slot
     */
    List<Reservation> revert(Instant heldUntil) {
        require(Status.ASSEMBLED);
        List<Reservation> kept = members();
        kept.forEach(r -> r.hold(heldUntil));
        this.status = Status.FORMING;
        this.readyBy = null;
        return kept;
    }

    public boolean readyCheckMissed(Instant now) {
        return status == Status.ASSEMBLED && !now.isBefore(readyBy);
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Party " + id + " is " + status + ", not " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getExpeditionId() {
        return expeditionId;
    }

    public int getNumber() {
        return number;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getReadyBy() {
        return readyBy;
    }

    public List<Reservation> getReservations() {
        return reservations;
    }
}
