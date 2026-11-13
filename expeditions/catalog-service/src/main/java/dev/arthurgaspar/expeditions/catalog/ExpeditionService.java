package dev.arthurgaspar.expeditions.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Topics;
import dev.arthurgaspar.expeditions.platform.outbox.Outbox;
import dev.arthurgaspar.expeditions.platform.web.ApiException;

@Service
public class ExpeditionService {

    public static final int MAX_PARTY_SIZE = 8;

    public record Draft(String code, String name, String dungeon, String difficulty, Instant opensAt, int parties,
                        Map<Role, Integer> composition) {}

    public record View(long id, String code, String name, String dungeon, String dungeonName, String difficulty,
                       Instant opensAt, int parties, Map<Role, Integer> composition, int partySize, int totalSlots) {}

    private final ExpeditionRepository expeditions;
    private final GameData game;
    private final Outbox outbox;
    private final Clock clock;

    public ExpeditionService(ExpeditionRepository expeditions, GameData game, Outbox outbox, Clock clock) {
        this.expeditions = expeditions;
        this.game = game;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public View publish(String staffId, Draft draft) {
        game.dungeon(draft.dungeon());
        game.difficulty(draft.difficulty());
        int size = draft.composition().values().stream().mapToInt(Integer::intValue).sum();
        if (draft.composition().values().stream().anyMatch(n -> n < 0) || size < 2 || size > MAX_PARTY_SIZE) {
            throw ApiException.invalid("expedition.composition-invalid",
                    "A party needs 2 to " + MAX_PARTY_SIZE + " members, with no negative role counts");
        }
        if (expeditions.existsByCode(draft.code())) {
            throw ApiException.conflict("expedition.code-taken", "An expedition with code " + draft.code() + " exists");
        }
        Expedition e = expeditions.save(new Expedition(draft.code(), draft.name(), draft.dungeon(), draft.difficulty(),
                draft.opensAt(), draft.parties(), draft.composition(), staffId, clock.instant()));
        outbox.append(Topics.EXPEDITIONS, e.getId().toString(), Events.EXPEDITION_PUBLISHED,
                new ExpeditionPublished(e.getId(), e.getCode(), e.getName(), e.getDungeon(), e.getDifficulty(),
                        e.getOpensAt(), e.getParties(), e.composition()));
        return view(e);
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        return expeditions.findAllByOrderByOpensAtAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public View get(long id) {
        return expeditions.findById(id).map(this::view)
                .orElseThrow(() -> ApiException.notFound("expedition.not-found", "Expedition not found"));
    }

    private View view(Expedition e) {
        return new View(e.getId(), e.getCode(), e.getName(), e.getDungeon(), game.dungeon(e.getDungeon()).name(),
                e.getDifficulty(), e.getOpensAt(), e.getParties(), e.composition(), e.partySize(),
                e.getParties() * e.partySize());
    }
}
