package dev.arthurgaspar.guildledger.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "species_id", nullable = false)
    private int speciesId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String username, String displayName, int speciesId, String passwordHash, Instant createdAt) {
        this.username = username;
        this.displayName = displayName;
        this.speciesId = speciesId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSpeciesId() {
        return speciesId;
    }

    public void setProfile(String displayName, int speciesId) {
        this.displayName = displayName;
        this.speciesId = speciesId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
