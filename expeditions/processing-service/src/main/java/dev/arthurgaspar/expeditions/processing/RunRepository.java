package dev.arthurgaspar.expeditions.processing;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RunRepository extends JpaRepository<Run, String> {

    @Query("""
            select r.id from Run r
            where r.status = :pending and r.nextAttemptAt <= :now and (r.leaseUntil is null or r.leaseUntil <= :now)
            order by r.nextAttemptAt""")
    List<String> due(Instant now, Run.Status pending, Limit limit);

    /**
     * Takes a run for one attempt. The condition makes it safe across instances: only one update sees the lease free.
     *
     * @return 1 if this caller got the run
     */
    @Modifying
    @Query("""
            update Run r set r.leaseUntil = :until
            where r.id = :id and r.status = :pending and (r.leaseUntil is null or r.leaseUntil <= :now)""")
    int claim(String id, Instant now, Instant until, Run.Status pending);

    List<Run> findByExpeditionIdAndPartyIdOrderByCreatedAt(Long expeditionId, Long partyId);

    default List<String> due(Instant now, int limit) {
        return due(now, Run.Status.PENDING, Limit.of(limit));
    }

    default boolean claim(String id, Instant now, Instant until) {
        return claim(id, now, until, Run.Status.PENDING) == 1;
    }
}
