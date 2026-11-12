package dev.arthurgaspar.guildledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "balances")
public class Balance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "account_key", nullable = false)
    private String accountKey;

    @Column(nullable = false)
    private String asset;

    @Column(nullable = false)
    private long amount;

    protected Balance() {
    }

    Balance(Long teamId, String accountKey, String asset) {
        this.teamId = teamId;
        this.accountKey = accountKey;
        this.asset = asset;
    }

    public String getAccountKey() {
        return accountKey;
    }

    public String getAsset() {
        return asset;
    }

    public long getAmount() {
        return amount;
    }

    void apply(long delta) {
        this.amount += delta;
    }
}
