package dev.arthurgaspar.expeditions.platform.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.arthurgaspar.expeditions.platform.Role;

/** Event payloads and their type names. See docs/design.md, "Event contracts". */
public final class Events {

    private Events() {
    }

    public static final String EXPEDITION_PUBLISHED = "expedition.published";
    public static final String CAPACITY_CHANGED = "capacity.changed";
    public static final String RESERVATION_HELD = "reservation.held";
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";
    public static final String RESERVATION_RELEASED = "reservation.released";
    public static final String PARTY_ASSEMBLED = "party.assembled";
    public static final String PARTY_REVERTED = "party.reverted";
    public static final String PARTY_LOCKED = "party.locked";

    public record ExpeditionPublished(long id, String code, String name, String dungeon, String difficulty,
                                      Instant opensAt, int parties, Map<Role, Integer> composition) {}

    public record CapacityChanged(long expeditionId, int openSlots, long version) {}

    public record ReservationHeld(long reservationId, long expeditionId, long partyId, String adventurerId,
                                  Role role, Instant heldUntil) {}

    public record ReservationConfirmed(long reservationId, long expeditionId, long partyId, String adventurerId) {}

    public record ReservationReleased(long reservationId, long expeditionId, long partyId, String adventurerId,
                                      Role role, String reason) {}

    public record Member(long reservationId, String adventurerId, Role role) {}

    public record PartyAssembled(long expeditionId, long partyId, Instant readyBy, List<Member> members) {}

    public record PartyReverted(long expeditionId, long partyId, List<String> failed, List<String> kept) {}

    public record PartyLocked(long expeditionId, long partyId, List<Member> members) {}
}
