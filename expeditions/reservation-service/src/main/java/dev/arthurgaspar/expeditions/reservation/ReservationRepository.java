package dev.arthurgaspar.expeditions.reservation;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import dev.arthurgaspar.expeditions.reservation.Reservation.Status;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Collection<Status> ACTIVE = EnumSet.of(Status.HELD, Status.CONFIRMED, Status.LOCKED);

    @Query("select r.expeditionId from Reservation r where r.id = :id")
    Optional<Long> findExpeditionId(Long id);

    boolean existsByExpeditionIdAndAdventurerIdAndStatusIn(Long expeditionId, String adventurerId, Collection<Status> statuses);

    List<Reservation> findByAdventurerIdAndStatusInOrderById(String adventurerId, Collection<Status> statuses);

    default boolean hasActive(Long expeditionId, String adventurerId) {
        return existsByExpeditionIdAndAdventurerIdAndStatusIn(expeditionId, adventurerId, ACTIVE);
    }

    default List<Reservation> findActiveFor(String adventurerId) {
        return findByAdventurerIdAndStatusInOrderById(adventurerId, ACTIVE);
    }

    /** Holds that lapsed in parties still forming; a party in its ready check runs on its own deadline instead. */
    @Query("""
            select distinct r.expeditionId from Reservation r
            where r.status = :held and r.heldUntil <= :now and r.party.status = :forming""")
    List<Long> expeditionsWithLapsedHolds(Instant now, Status held, Party.Status forming);

    @Query("""
            select r from Reservation r join fetch r.party p
            where r.expeditionId = :expeditionId and r.status = :held and r.heldUntil <= :now and p.status = :forming
            order by r.id""")
    List<Reservation> lapsedHolds(Long expeditionId, Instant now, Status held, Party.Status forming);

    default List<Long> expeditionsWithLapsedHolds(Instant now) {
        return expeditionsWithLapsedHolds(now, Status.HELD, Party.Status.FORMING);
    }

    default List<Reservation> lapsedHolds(Long expeditionId, Instant now) {
        return lapsedHolds(expeditionId, now, Status.HELD, Party.Status.FORMING);
    }
}
