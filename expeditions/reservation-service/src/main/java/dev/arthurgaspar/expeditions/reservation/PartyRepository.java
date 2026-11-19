package dev.arthurgaspar.expeditions.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PartyRepository extends JpaRepository<Party, Long> {

    @EntityGraph(attributePaths = "reservations")
    List<Party> findByExpeditionIdAndStatusOrderByNumber(Long expeditionId, Party.Status status);

    @EntityGraph(attributePaths = "reservations")
    @Query("select p from Party p where p.id = :id")
    Optional<Party> findWithMembers(Long id);

    @Query("select distinct p.expeditionId from Party p where p.status = :assembled and p.readyBy <= :now")
    List<Long> expeditionsWithMissedReadyChecks(Instant now, Party.Status assembled);

    @EntityGraph(attributePaths = "reservations")
    @Query("select p from Party p where p.expeditionId = :expeditionId and p.status = :assembled and p.readyBy <= :now order by p.number")
    List<Party> missedReadyChecks(Long expeditionId, Instant now, Party.Status assembled);

    @Query("select p.status, count(p) from Party p where p.expeditionId = :expeditionId group by p.status")
    List<Object[]> countByStatus(Long expeditionId);

    default List<Long> expeditionsWithMissedReadyChecks(Instant now) {
        return expeditionsWithMissedReadyChecks(now, Party.Status.ASSEMBLED);
    }

    default List<Party> missedReadyChecks(Long expeditionId, Instant now) {
        return missedReadyChecks(expeditionId, now, Party.Status.ASSEMBLED);
    }
}
