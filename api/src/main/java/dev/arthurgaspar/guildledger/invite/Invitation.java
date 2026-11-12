package dev.arthurgaspar.guildledger.invite;

import java.time.Instant;

import dev.arthurgaspar.guildledger.auth.User;
import dev.arthurgaspar.guildledger.team.Role;
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

@Entity
@Table(name = "invitations")
public class Invitation {

    public enum Status { PENDING, ACCEPTED, DECLINED, REVOKED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_id")
    private User invitee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected Invitation() {
    }

    Invitation(Long teamId, User invitee, User invitedBy, Role role, Instant createdAt, Instant expiresAt) {
        this.teamId = teamId;
        this.invitee = invitee;
        this.invitedBy = invitedBy;
        this.role = role;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Expiry is applied lazily: a pending invitation past its date reads as expired. */
    public Status statusAt(Instant now) {
        return status == Status.PENDING && !now.isBefore(expiresAt) ? Status.EXPIRED : status;
    }

    void close(Status status, Instant at) {
        this.status = status;
        this.respondedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public User getInvitee() {
        return invitee;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
