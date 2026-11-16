package dev.arthurgaspar.expeditions.issuance;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adventurer_id", nullable = false)
    private String adventurerId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String message;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(String adventurerId, String kind, String message, long expeditionId, Instant createdAt) {
        this.adventurerId = adventurerId;
        this.kind = kind;
        this.message = message.length() > 300 ? message.substring(0, 300) : message;
        this.expeditionId = expeditionId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getAdventurerId() {
        return adventurerId;
    }

    public String getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }

    public Long getExpeditionId() {
        return expeditionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
