package dev.arthurgaspar.guildledger.team;

import java.time.Instant;

import dev.arthurgaspar.guildledger.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "team_events")
public class TeamEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TeamEvent() {
    }

    TeamEvent(Long teamId, User actor, String type, String summary, Instant createdAt) {
        this.teamId = teamId;
        this.actor = actor;
        this.type = type;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getActor() {
        return actor;
    }

    public String getType() {
        return type;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
