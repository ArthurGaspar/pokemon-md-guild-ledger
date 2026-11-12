package dev.arthurgaspar.guildledger.ledger;

import java.time.Instant;

import dev.arthurgaspar.guildledger.auth.User;
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

/** Append-only. Entries are never updated or deleted. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private String txn;

    @Column(name = "account_key", nullable = false)
    private String accountKey;

    @Column(nullable = false)
    private String asset;

    @Column(nullable = false)
    private long delta;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @Column(name = "mission_id")
    private Long missionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(Long teamId, String txn, String accountKey, String asset, long delta, long balanceAfter,
                Reason reason, Long missionId, User actor, String memo, Instant createdAt) {
        this.teamId = teamId;
        this.txn = txn;
        this.accountKey = accountKey;
        this.asset = asset;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.missionId = missionId;
        this.actor = actor;
        this.memo = memo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTxn() {
        return txn;
    }

    public String getAccountKey() {
        return accountKey;
    }

    public String getAsset() {
        return asset;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Reason getReason() {
        return reason;
    }

    public Long getMissionId() {
        return missionId;
    }

    public User getActor() {
        return actor;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
