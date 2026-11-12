package dev.arthurgaspar.guildledger.recruit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecruitRepository extends JpaRepository<Recruit, Long> {

    @Query("select r from Recruit r left join fetch r.recruitedBy m left join fetch m.user where r.teamId = :teamId and r.status <> 'RELEASED' order by r.id")
    List<Recruit> findActive(Long teamId);

    @Query("select count(r) from Recruit r where r.teamId = :teamId and r.status <> 'RELEASED'")
    long countActive(Long teamId);

    /** Rows of [membershipId, count]. */
    @Query("select r.recruitedBy.id, count(r) from Recruit r where r.teamId = :teamId and r.status <> 'RELEASED' and r.recruitedBy is not null group by r.recruitedBy.id")
    List<Object[]> countActiveByRecruiter(Long teamId);
}
