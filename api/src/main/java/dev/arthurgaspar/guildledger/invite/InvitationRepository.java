package dev.arthurgaspar.guildledger.invite;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface InvitationRepository extends JpaRepository<Invitation, Long> {

    @Query("""
            select i from Invitation i join fetch i.invitee join fetch i.invitedBy
            where i.invitee.id = :userId and i.status = 'PENDING' and i.expiresAt > :now order by i.id desc""")
    List<Invitation> findOpenFor(Long userId, Instant now);

    @Query("select count(i) from Invitation i where i.invitee.id = :userId and i.status = 'PENDING' and i.expiresAt > :now")
    long countOpenFor(Long userId, Instant now);

    @Query("""
            select count(i) > 0 from Invitation i
            where i.teamId = :teamId and i.invitee.id = :userId and i.status = 'PENDING' and i.expiresAt > :now""")
    boolean existsOpen(Long teamId, Long userId, Instant now);

    @Query("select i from Invitation i join fetch i.invitee join fetch i.invitedBy where i.teamId = :teamId order by i.id desc")
    List<Invitation> findByTeam(Long teamId, Limit limit);
}
