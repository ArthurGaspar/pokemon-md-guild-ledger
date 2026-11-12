package dev.arthurgaspar.guildledger.team;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "rank_points", nullable = false)
    private int rankPoints;

    @Column(name = "treasury_cut_pct", nullable = false)
    private int treasuryCutPct = 20;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false)
    private SplitMode splitMode = SplitMode.EQUAL;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Team() {
    }

    public Team(String name, Instant createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getRankPoints() {
        return rankPoints;
    }

    public void addRankPoints(int points) {
        this.rankPoints += points;
    }

    public int getTreasuryCutPct() {
        return treasuryCutPct;
    }

    public SplitMode getSplitMode() {
        return splitMode;
    }

    public void setPolicy(int treasuryCutPct, SplitMode splitMode) {
        this.treasuryCutPct = treasuryCutPct;
        this.splitMode = splitMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
