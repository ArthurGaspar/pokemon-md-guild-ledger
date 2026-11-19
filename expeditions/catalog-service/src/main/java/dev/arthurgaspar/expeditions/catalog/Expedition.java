package dev.arthurgaspar.expeditions.catalog;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import dev.arthurgaspar.expeditions.platform.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expeditions")
public class Expedition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String dungeon;

    @Column(nullable = false)
    private String difficulty;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(nullable = false)
    private int parties;

    @Column(nullable = false)
    private int tanks;

    @Column(nullable = false)
    private int healers;

    @Column(nullable = false)
    private int damage;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Expedition() {
    }

    public Expedition(String code, String name, String dungeon, String difficulty, Instant opensAt, int parties,
                      Map<Role, Integer> composition, String createdBy, Instant createdAt) {
        this.code = code;
        this.name = name;
        this.dungeon = dungeon;
        this.difficulty = difficulty;
        this.opensAt = opensAt;
        this.parties = parties;
        this.tanks = composition.getOrDefault(Role.TANK, 0);
        this.healers = composition.getOrDefault(Role.HEALER, 0);
        this.damage = composition.getOrDefault(Role.DAMAGE, 0);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Map<Role, Integer> composition() {
        Map<Role, Integer> c = new EnumMap<>(Role.class);
        c.put(Role.TANK, tanks);
        c.put(Role.HEALER, healers);
        c.put(Role.DAMAGE, damage);
        return c;
    }

    public int partySize() {
        return tanks + healers + damage;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDungeon() {
        return dungeon;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public int getParties() {
        return parties;
    }
}
