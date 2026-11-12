package dev.arthurgaspar.guildledger.mission;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    List<Mission> findByTeamIdOrderByIdDesc(Long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Mission m where m.id = :id")
    Optional<Mission> lockById(Long id);

    @Query("select count(m) from Mission m where m.leader.id = :membershipId and m.status in ('PLANNED', 'DEPARTED')")
    long countOpenLedBy(Long membershipId);

    @Query("select count(m) from Mission m join m.party p where p = :recruitId and m.status = 'PLANNED'")
    long countPlannedWithRecruit(Long recruitId);

    @Query("select m from Mission m join m.party p where p = :recruitId and m.status = 'PLANNED' and m.id <> :excludeId")
    List<Mission> findOtherPlansWith(Long recruitId, Long excludeId);
}
