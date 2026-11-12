package dev.arthurgaspar.guildledger.team;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    @Query("select m from Membership m join fetch m.user where m.teamId = :teamId and m.leftAt is null order by m.id")
    List<Membership> findActive(Long teamId);

    @Query("select m from Membership m where m.user.id = :userId and m.leftAt is null order by m.id")
    List<Membership> findActiveByUser(Long userId);

    @Query("select m from Membership m where m.teamId = :teamId and m.user.id = :userId and m.leftAt is null")
    Optional<Membership> findActive(Long teamId, Long userId);

    @Query("select count(m) from Membership m where m.teamId = :teamId and m.leftAt is null")
    long countActive(Long teamId);
}
