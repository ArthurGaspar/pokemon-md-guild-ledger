package dev.arthurgaspar.guildledger.team;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface TeamEventRepository extends JpaRepository<TeamEvent, Long> {

    List<TeamEvent> findByTeamIdOrderByIdDesc(Long teamId, Limit limit);
}
