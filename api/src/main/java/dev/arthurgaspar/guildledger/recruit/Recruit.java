package dev.arthurgaspar.guildledger.recruit;

import java.time.Instant;

import dev.arthurgaspar.guildledger.team.Membership;
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
import jakarta.persistence.Version;

@Entity
@Table(name = "recruits")
public class Recruit {

    public enum Status { AVAILABLE, ON_MISSION, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "species_id", nullable = false)
    private int speciesId;

    private String nickname;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false)
    private int exp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recruited_by_id")
    private Membership recruitedBy;

    @Column(name = "recruited_at", nullable = false)
    private Instant recruitedAt;

    @Column(name = "mission_id")
    private Long missionId;

    @Version
    private long version;

    protected Recruit() {
    }

    public Recruit(Long teamId, int speciesId, String nickname, int level, Membership recruitedBy, Instant recruitedAt) {
        this.teamId = teamId;
        this.speciesId = speciesId;
        this.nickname = nickname;
        this.level = level;
        this.exp = Leveling.expForLevel(level);
        this.recruitedBy = recruitedBy;
        this.recruitedAt = recruitedAt;
    }

    /** @return levels gained */
    public int gainExp(int amount, int maxLevel) {
        int before = level;
        exp += amount;
        level = Math.min(maxLevel, Math.max(level, Leveling.levelForExp(exp)));
        return level - before;
    }

    public void sendOn(long missionId) {
        this.status = Status.ON_MISSION;
        this.missionId = missionId;
    }

    public void returnHome() {
        this.status = Status.AVAILABLE;
        this.missionId = null;
    }

    public void release() {
        this.status = Status.RELEASED;
    }

    public Long getId() {
        return id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public int getSpeciesId() {
        return speciesId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public int getLevel() {
        return level;
    }

    public int getExp() {
        return exp;
    }

    public Status getStatus() {
        return status;
    }

    public Membership getRecruitedBy() {
        return recruitedBy;
    }

    public Instant getRecruitedAt() {
        return recruitedAt;
    }

    public Long getMissionId() {
        return missionId;
    }
}
