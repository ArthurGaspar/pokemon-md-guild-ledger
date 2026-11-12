package dev.arthurgaspar.guildledger.team;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.guildledger.auth.UserRepository;

/** Human-readable team logbook: joins, role changes, missions, payouts. */
@Component
public class EventLog {

    public record View(long id, String type, String summary, String actor, Instant createdAt) {}

    private final TeamEventRepository repo;
    private final UserRepository users;
    private final Clock clock;

    EventLog(TeamEventRepository repo, UserRepository users, Clock clock) {
        this.repo = repo;
        this.users = users;
        this.clock = clock;
    }

    public void record(long teamId, Long actorUserId, String type, String summary) {
        var actor = actorUserId == null ? null : users.getReferenceById(actorUserId);
        repo.save(new TeamEvent(teamId, actor, type, summary, clock.instant()));
    }

    public List<View> recent(long teamId, int limit) {
        return repo.findByTeamIdOrderByIdDesc(teamId, Limit.of(Math.clamp(limit, 1, 200))).stream()
                .map(e -> new View(e.getId(), e.getType(), e.getSummary(),
                        e.getActor() == null ? null : e.getActor().getDisplayName(), e.getCreatedAt()))
                .toList();
    }
}
