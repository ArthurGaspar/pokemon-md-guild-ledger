package dev.arthurgaspar.expeditions.reservation;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import dev.arthurgaspar.expeditions.platform.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Reservation's copy of an expedition, built from {@code expedition.published}. Every change to its parties locks
 * this row first, which serializes allocation, the ready check and the open-slot counter per expedition.
 */
@Entity
@Table(name = "expeditions")
public class ExpeditionInventory {

    @Id
    private Long id;

    @Column(nullable = false)
    private String code;

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

    @Column(name = "open_slots", nullable = false)
    private int openSlots;

    /** Bumped on every change to {@code openSlots}, so consumers can drop stale capacity events. */
    @Column(name = "capacity_version", nullable = false)
    private long capacityVersion;

    protected ExpeditionInventory() {
    }

    public ExpeditionInventory(long id, String code, Instant opensAt, int parties, Map<Role, Integer> composition) {
        this.id = id;
        this.code = code;
        this.opensAt = opensAt;
        this.parties = parties;
        this.tanks = composition.getOrDefault(Role.TANK, 0);
        this.healers = composition.getOrDefault(Role.HEALER, 0);
        this.damage = composition.getOrDefault(Role.DAMAGE, 0);
        this.openSlots = parties * partySize();
        this.capacityVersion = 1;
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

    public boolean isOpenAt(Instant now) {
        return !now.isBefore(opensAt);
    }

    void takeSlot() {
        if (openSlots == 0) {
            throw new IllegalStateException("Expedition " + id + " has no open slot to take");
        }
        openSlots--;
        capacityVersion++;
    }

    void returnSlots(int count) {
        if (count == 0) {
            return;
        }
        openSlots += count;
        capacityVersion++;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public int getParties() {
        return parties;
    }

    public int getOpenSlots() {
        return openSlots;
    }

    public long getCapacityVersion() {
        return capacityVersion;
    }
}
