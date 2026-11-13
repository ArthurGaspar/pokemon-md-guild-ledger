package dev.arthurgaspar.expeditions.reservation;

import java.time.Instant;

import dev.arthurgaspar.expeditions.platform.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One adventurer's claim on one role in one party. Created through {@link Party#join}. */
@Entity
@Table(name = "reservations")
public class Reservation {

    public enum Status {
        HELD, CONFIRMED, LOCKED,
        EXPIRED, DECLINED, CANCELLED;

        /** Occupies a slot. The rest are closed and keep the slot free. */
        public boolean active() {
            return this == HELD || this == CONFIRMED || this == LOCKED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id")
    private Party party;

    @Column(name = "adventurer_id", nullable = false)
    private String adventurerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** When a hold in a forming party lapses. Not used while the party is in its ready check. */
    @Column(name = "held_until")
    private Instant heldUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Reservation() {
    }

    Reservation(Party party, String adventurerId, Role role, Instant now, Instant heldUntil) {
        this.expeditionId = party.getExpeditionId();
        this.party = party;
        this.adventurerId = adventurerId;
        this.role = role;
        this.status = Status.HELD;
        this.heldUntil = heldUntil;
        this.createdAt = now;
    }

    void confirm() {
        require(Status.HELD);
        status = Status.CONFIRMED;
    }

    void lock() {
        require(Status.CONFIRMED);
        status = Status.LOCKED;
        heldUntil = null;
    }

    /** The party's formation was undone by someone else; this member keeps the slot with a fresh hold. */
    void hold(Instant until) {
        if (status != Status.HELD && status != Status.CONFIRMED) {
            throw new IllegalStateException("Reservation " + id + " is " + status);
        }
        status = Status.HELD;
        heldUntil = until;
    }

    void close(Status outcome, Instant now) {
        if (outcome.active() || !status.active() || status == Status.LOCKED) {
            throw new IllegalStateException("Reservation " + id + " can't go from " + status + " to " + outcome);
        }
        status = outcome;
        heldUntil = null;
        closedAt = now;
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Reservation " + id + " is " + status + ", not " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getExpeditionId() {
        return expeditionId;
    }

    public Party getParty() {
        return party;
    }

    public String getAdventurerId() {
        return adventurerId;
    }

    public Role getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
