package dev.arthurgaspar.expeditions.issuance;

import java.time.Instant;

import dev.arthurgaspar.expeditions.platform.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Proof that an adventurer cleared an expedition, with their share of the loot. */
@Entity
@Table(name = "certificates")
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "adventurer_id", nullable = false)
    private String adventurerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "floors_cleared", nullable = false)
    private int floorsCleared;

    @Column(name = "poke_share", nullable = false)
    private int pokeShare;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected Certificate() {
    }

    public Certificate(String runId, long expeditionId, long partyId, String adventurerId, Role role, int floorsCleared,
                       int pokeShare, Instant issuedAt) {
        this.runId = runId;
        this.expeditionId = expeditionId;
        this.partyId = partyId;
        this.adventurerId = adventurerId;
        this.role = role;
        this.floorsCleared = floorsCleared;
        this.pokeShare = pokeShare;
        this.issuedAt = issuedAt;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public Long getExpeditionId() {
        return expeditionId;
    }

    public Long getPartyId() {
        return partyId;
    }

    public String getAdventurerId() {
        return adventurerId;
    }

    public Role getRole() {
        return role;
    }

    public int getFloorsCleared() {
        return floorsCleared;
    }

    public int getPokeShare() {
        return pokeShare;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
