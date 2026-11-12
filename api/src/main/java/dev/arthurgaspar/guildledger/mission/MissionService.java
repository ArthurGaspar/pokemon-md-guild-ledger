package dev.arthurgaspar.guildledger.mission;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.ledger.Accounts;
import dev.arthurgaspar.guildledger.ledger.LedgerService;
import dev.arthurgaspar.guildledger.ledger.LedgerService.Leg;
import dev.arthurgaspar.guildledger.ledger.Reason;
import dev.arthurgaspar.guildledger.recruit.Leveling;
import dev.arthurgaspar.guildledger.recruit.Recruit;
import dev.arthurgaspar.guildledger.recruit.RecruitRepository;
import dev.arthurgaspar.guildledger.recruit.RecruitService;
import dev.arthurgaspar.guildledger.team.EventLog;
import dev.arthurgaspar.guildledger.team.Membership;
import dev.arthurgaspar.guildledger.team.MembershipRepository;
import dev.arthurgaspar.guildledger.team.Role;
import dev.arthurgaspar.guildledger.team.Team;
import dev.arthurgaspar.guildledger.team.TeamAccess;
import dev.arthurgaspar.guildledger.team.TeamRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class MissionService {

    public record PostRequest(@NotBlank @Size(max = 80) String title, @NotBlank String kind,
                              @NotBlank String dungeonCode, int floor, @NotBlank String difficulty,
                              Integer clientSpeciesId, @Min(0) @Max(100_000) int rewardPoke,
                              List<@Valid ItemStack> rewardItems) {}

    public record PlanRequest(long leaderMembershipId, List<Long> recruitIds, List<@Valid ItemStack> bag) {}

    public enum Outcome { COMPLETED, FAILED }

    public record ReportRequest(@NotNull Outcome outcome, List<@Valid ItemStack> itemsUsed,
                                @Size(max = 280) String note) {}

    public record Person(long membershipId, String displayName, int speciesId) {}

    public record PartySlot(long recruitId, int speciesId, String name, int level, Long recruitedBy) {}

    public record View(long id, String title, String kind, String dungeonCode, String dungeonName, int floor,
                       String floorLabel, String difficulty, Integer clientSpeciesId, String clientName,
                       int rewardPoke, List<ItemStack> rewardItems, int rankPoints, Mission.Status status,
                       Person leader, List<PartySlot> party, List<ItemStack> bag, String postedBy, Instant postedAt,
                       Instant departedAt, Instant closedAt, String outcomeNote) {}

    public record ShareView(long membershipId, String displayName, int speciesId, int weight, long amount) {}

    public record LevelUp(long recruitId, String name, int from, int to) {}

    public record Preview(int rewardPoke, int treasuryCutPct, String splitMode, long treasury, List<ShareView> shares,
                          int rankPoints, String rankBefore, String rankAfter, int expPerRecruit,
                          List<LevelUp> levelUps) {}

    private final MissionRepository missions;
    private final RecruitRepository recruits;
    private final TeamRepository teams;
    private final MembershipRepository memberships;
    private final TeamAccess access;
    private final LedgerService ledger;
    private final EventLog events;
    private final Catalog catalog;
    private final Clock clock;

    MissionService(MissionRepository missions, RecruitRepository recruits, TeamRepository teams,
                   MembershipRepository memberships, TeamAccess access, LedgerService ledger, EventLog events,
                   Catalog catalog, Clock clock) {
        this.missions = missions;
        this.recruits = recruits;
        this.teams = teams;
        this.memberships = memberships;
        this.access = access;
        this.ledger = ledger;
        this.events = events;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list(long teamId, long userId) {
        access.member(teamId, userId);
        return missions.findByTeamIdOrderByIdDesc(teamId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public View get(long teamId, long userId, long missionId) {
        access.member(teamId, userId);
        return view(find(teamId, missionId));
    }

    @Transactional
    public View post(long teamId, long userId, PostRequest req) {
        Membership me = access.atLeast(teamId, userId, Role.OFFICER);
        Team team = teams.findById(teamId).orElseThrow();
        if (!catalog.data().missionKinds().contains(req.kind())) {
            throw ApiException.invalid("mission.kind-unknown", "Unknown mission kind " + req.kind());
        }
        Catalog.Dungeon dungeon = catalog.dungeon(req.dungeonCode());
        if (req.floor() < 1 || req.floor() > dungeon.floors()) {
            throw ApiException.invalid("mission.floor-out-of-range",
                    dungeon.name() + " has floors 1 to " + dungeon.floors());
        }
        Catalog.Difficulty difficulty = catalog.difficulty(req.difficulty());
        Catalog.Rank rank = catalog.rankFor(team.getRankPoints());
        if (catalog.difficultyOrder(difficulty.code()) > catalog.difficultyOrder(rank.maxDifficulty())) {
            throw ApiException.invalid("mission.difficulty-locked", rank.name() + " Rank teams can take jobs up to "
                    + catalog.difficulty(rank.maxDifficulty()).label());
        }
        if (req.clientSpeciesId() != null) {
            catalog.species(req.clientSpeciesId());
        }
        Mission m = missions.save(new Mission(teamId, req.title().strip(), req.kind(), dungeon.code(), req.floor(),
                difficulty.code(), req.clientSpeciesId(), req.rewardPoke(), difficulty.rankPoints(),
                merge(req.rewardItems()), me, clock.instant()));
        events.record(teamId, userId, "mission.posted", "Posted " + difficulty.label() + " job: " + m.getTitle());
        return view(m);
    }

    @Transactional
    public View plan(long teamId, long userId, long missionId, PlanRequest req) {
        access.atLeast(teamId, userId, Role.OFFICER);
        Mission m = lock(teamId, missionId);
        requireStatus(m, Mission.Status.POSTED, Mission.Status.PLANNED);
        Team team = teams.findById(teamId).orElseThrow();
        Catalog.Rank rank = catalog.rankFor(team.getRankPoints());

        Membership leader = memberships.findById(req.leaderMembershipId())
                .filter(x -> x.getTeamId() == teamId && x.isActive())
                .orElseThrow(() -> ApiException.invalid("party.leader-invalid", "The mission leader must be a current member"));

        List<Long> ids = req.recruitIds() == null ? List.of() : req.recruitIds();
        if (ids.isEmpty() || ids.size() > catalog.limits().partySize() || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.invalid("party.size", "A party has 1 to " + catalog.limits().partySize() + " different recruits");
        }
        for (Long id : ids) {
            Recruit r = recruitOf(teamId, id);
            if (r.getStatus() != Recruit.Status.AVAILABLE) {
                throw ApiException.conflict("party.recruit-busy", name(r) + " is out on another mission");
            }
            List<Mission> other = missions.findOtherPlansWith(id, missionId);
            if (!other.isEmpty()) {
                throw ApiException.conflict("party.recruit-busy",
                        name(r) + " is already planned for \"" + other.getFirst().getTitle() + "\"");
            }
        }

        List<ItemStack> bag = merge(req.bag());
        int carried = bag.stream().mapToInt(ItemStack::quantity).sum();
        if (carried > rank.bagCap()) {
            throw ApiException.invalid("bag.over-capacity", rank.name() + " Rank bags hold " + rank.bagCap() + " items");
        }
        Map<String, Long> storage = ledger.holdings(teamId, Accounts.STORAGE);
        for (ItemStack s : bag) {
            long have = storage.getOrDefault(s.itemCode(), 0L);
            if (have < s.quantity()) {
                throw ApiException.invalid("ledger.insufficient-stock", "Storage has " + have + " × "
                        + catalog.item(s.itemCode()).name() + "; " + s.quantity() + " needed");
            }
        }

        boolean replan = m.getStatus() == Mission.Status.PLANNED;
        m.plan(leader, ids, bag);
        events.record(teamId, userId, "mission.planned", (replan ? "Re-planned " : "Planned ") + m.getTitle()
                + ": led by " + leader.displayName() + ", party of " + ids.size());
        return view(m);
    }

    @Transactional
    public View depart(long teamId, long userId, long missionId) {
        Mission m = lock(teamId, missionId);
        requireLeaderOrOfficer(teamId, userId, m);
        requireStatus(m, Mission.Status.PLANNED);
        for (Long id : m.getParty()) {
            Recruit r = recruitOf(teamId, id);
            if (r.getStatus() != Recruit.Status.AVAILABLE) {
                throw ApiException.conflict("party.recruit-busy", name(r) + " is not available");
            }
            r.sendOn(m.getId());
        }
        List<Leg> legs = new ArrayList<>();
        for (ItemStack s : m.getBag()) {
            legs.addAll(Leg.transfer(Accounts.STORAGE, Accounts.bag(m.getId()), s.itemCode(), s.quantity()));
        }
        ledger.post(teamId, userId, Reason.MISSION_PACK, m.getId(), "Packed for " + m.getTitle(), legs);
        m.depart(clock.instant());
        Catalog.Dungeon d = catalog.dungeon(m.getDungeonCode());
        events.record(teamId, userId, "mission.departed", "Party left for " + d.name() + " " + catalog.floorLabel(d, m.getFloor()));
        return view(m);
    }

    @Transactional(readOnly = true)
    public Preview preview(long teamId, long userId, long missionId) {
        access.member(teamId, userId);
        Mission m = find(teamId, missionId);
        Team team = teams.findById(teamId).orElseThrow();
        Payout.Result split = split(team, m);
        Map<Long, Membership> byId = memberships.findActive(teamId).stream()
                .collect(Collectors.toMap(Membership::getId, x -> x));
        Catalog.Difficulty diff = catalog.difficulty(m.getDifficulty());
        List<LevelUp> levelUps = new ArrayList<>();
        for (Long id : m.getParty()) {
            Recruit r = recruits.findById(id).orElseThrow();
            int to = Math.min(catalog.limits().maxLevel(),
                    Leveling.levelForExp(r.getExp() + diff.exp()));
            if (to > r.getLevel()) {
                levelUps.add(new LevelUp(r.getId(), name(r), r.getLevel(), to));
            }
        }
        return new Preview(m.getRewardPoke(), team.getTreasuryCutPct(), team.getSplitMode().name(), split.treasury(),
                split.shares().stream().map(s -> new ShareView(s.membershipId(), byId.get(s.membershipId()).displayName(),
                        byId.get(s.membershipId()).getUser().getSpeciesId(), s.weight(), s.amount())).toList(),
                m.getRankPoints(), catalog.rankFor(team.getRankPoints()).code(),
                catalog.rankFor(team.getRankPoints() + m.getRankPoints()).code(), diff.exp(), levelUps);
    }

    @Transactional
    public View report(long teamId, long userId, long missionId, ReportRequest req) {
        Mission m = lock(teamId, missionId);
        requireLeaderOrOfficer(teamId, userId, m);
        requireStatus(m, Mission.Status.DEPARTED);
        String bagAccount = Accounts.bag(m.getId());
        Map<String, Long> bag = ledger.holdings(teamId, bagAccount);

        if (req.outcome() == Outcome.FAILED) {
            List<Leg> lost = new ArrayList<>();
            bag.forEach((item, qty) -> lost.addAll(Leg.transfer(bagAccount, Accounts.WORLD, item, qty)));
            ledger.post(teamId, userId, Reason.ITEM_LOST, m.getId(), "Lost when the party fainted", lost);
            returnParty(teamId, m);
            m.close(Mission.Status.FAILED, note(req.note()), clock.instant());
            events.record(teamId, userId, "mission.failed", "Failed: " + m.getTitle()
                    + (bag.isEmpty() ? "" : ". The bag was lost."));
            return view(m);
        }

        Map<String, Long> used = new LinkedHashMap<>();
        for (ItemStack s : merge(req.itemsUsed())) {
            long packed = bag.getOrDefault(s.itemCode(), 0L);
            if (s.quantity() > packed) {
                throw ApiException.invalid("report.used-exceeds-bag", "Only " + packed + " × "
                        + catalog.item(s.itemCode()).name() + " were packed");
            }
            used.put(s.itemCode(), (long) s.quantity());
        }
        List<Leg> usedLegs = new ArrayList<>();
        List<Leg> returnLegs = new ArrayList<>();
        bag.forEach((item, qty) -> {
            long u = used.getOrDefault(item, 0L);
            usedLegs.addAll(Leg.transfer(bagAccount, Accounts.WORLD, item, u));
            returnLegs.addAll(Leg.transfer(bagAccount, Accounts.STORAGE, item, qty - u));
        });
        ledger.post(teamId, userId, Reason.ITEM_USED, m.getId(), "Used during " + m.getTitle(), usedLegs);
        ledger.post(teamId, userId, Reason.MISSION_RETURN, m.getId(), "Returned from " + m.getTitle(), returnLegs);

        Team team = teams.lockById(teamId).orElseThrow();
        Payout.Result split = split(team, m);
        List<Leg> reward = new ArrayList<>();
        reward.addAll(Leg.transfer(Accounts.WORLD, Accounts.TREASURY, Accounts.POKE, split.treasury()));
        for (Payout.Share s : split.shares()) {
            reward.addAll(Leg.transfer(Accounts.WORLD, Accounts.wallet(s.membershipId()), Accounts.POKE, s.amount()));
        }
        for (ItemStack s : m.getRewardItems()) {
            reward.addAll(Leg.transfer(Accounts.WORLD, Accounts.STORAGE, s.itemCode(), s.quantity()));
        }
        ledger.post(teamId, userId, Reason.MISSION_REWARD, m.getId(), "Reward for " + m.getTitle(), reward);

        Catalog.Rank before = catalog.rankFor(team.getRankPoints());
        team.addRankPoints(m.getRankPoints());
        Catalog.Rank after = catalog.rankFor(team.getRankPoints());

        int exp = catalog.difficulty(m.getDifficulty()).exp();
        List<String> grew = new ArrayList<>();
        for (Long id : m.getParty()) {
            Recruit r = recruitOf(teamId, id);
            int from = r.getLevel();
            if (r.gainExp(exp, catalog.limits().maxLevel()) > 0) {
                grew.add(name(r) + " Lv. " + from + "→" + r.getLevel());
            }
        }
        returnParty(teamId, m);
        m.close(Mission.Status.COMPLETED, note(req.note()), clock.instant());

        events.record(teamId, userId, "mission.completed", "Completed: " + m.getTitle() + ". +" + m.getRewardPoke()
                + " Poké, +" + m.getRankPoints() + " rank points");
        if (!grew.isEmpty()) {
            events.record(teamId, null, "recruit.level", String.join(", ", grew));
        }
        if (after != before) {
            events.record(teamId, null, "team.rank", "Team reached " + after.name() + " Rank");
        }
        return view(m);
    }

    @Transactional
    public View cancel(long teamId, long userId, long missionId) {
        access.atLeast(teamId, userId, Role.OFFICER);
        Mission m = lock(teamId, missionId);
        requireStatus(m, Mission.Status.POSTED, Mission.Status.PLANNED);
        m.close(Mission.Status.CANCELLED, null, clock.instant());
        events.record(teamId, userId, "mission.cancelled", "Took down: " + m.getTitle());
        return view(m);
    }

    private Payout.Result split(Team team, Mission m) {
        Set<Long> active = memberships.findActive(team.getId()).stream().map(Membership::getId).collect(Collectors.toSet());
        List<Long> recruiters = m.getParty().stream()
                .map(id -> recruits.findById(id).map(r -> r.getRecruitedBy() == null ? null : r.getRecruitedBy().getId()).orElse(null))
                .toList();
        Long leaderId = m.getLeader() == null ? null : m.getLeader().getId();
        return Payout.split(m.getRewardPoke(), team.getTreasuryCutPct(), team.getSplitMode(), leaderId, recruiters, active);
    }

    private void returnParty(long teamId, Mission m) {
        for (Long id : m.getParty()) {
            Recruit r = recruitOf(teamId, id);
            if (r.getStatus() == Recruit.Status.ON_MISSION) {
                r.returnHome();
            }
        }
    }

    private void requireLeaderOrOfficer(long teamId, long userId, Mission m) {
        Membership me = access.member(teamId, userId);
        boolean leads = m.getLeader() != null && m.getLeader().getId().equals(me.getId());
        if (!leads && !me.getRole().atLeast(Role.OFFICER)) {
            throw ApiException.forbidden("Only the mission leader or an officer can do this");
        }
    }

    private static void requireStatus(Mission m, Mission.Status... allowed) {
        for (Mission.Status s : allowed) {
            if (m.getStatus() == s) {
                return;
            }
        }
        throw ApiException.conflict("mission.bad-status", "This mission is " + m.getStatus().name().toLowerCase());
    }

    private Mission find(long teamId, long missionId) {
        return missions.findById(missionId).filter(m -> m.getTeamId() == teamId)
                .orElseThrow(() -> ApiException.notFound("mission.not-found", "Mission not found"));
    }

    private Mission lock(long teamId, long missionId) {
        return missions.lockById(missionId).filter(m -> m.getTeamId() == teamId)
                .orElseThrow(() -> ApiException.notFound("mission.not-found", "Mission not found"));
    }

    private Recruit recruitOf(long teamId, long recruitId) {
        return recruits.findById(recruitId)
                .filter(r -> r.getTeamId() == teamId && r.getStatus() != Recruit.Status.RELEASED)
                .orElseThrow(() -> ApiException.invalid("recruit.not-found", "Recruit #" + recruitId + " is not on this team"));
    }

    /** Validates item codes and folds duplicate lines together, keeping first-seen order. */
    private List<ItemStack> merge(List<ItemStack> stacks) {
        if (stacks == null) {
            return List.of();
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            catalog.item(s.itemCode());
            if (s.quantity() < 1) {
                throw ApiException.invalid("amount.invalid", "Quantities must be at least 1");
            }
            merged.merge(s.itemCode(), s.quantity(), Integer::sum);
        }
        return merged.entrySet().stream().map(e -> new ItemStack(e.getKey(), e.getValue())).toList();
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    private String name(Recruit r) {
        return RecruitService.label(r, catalog.species(r.getSpeciesId()));
    }

    private View view(Mission m) {
        Catalog.Dungeon d = catalog.dungeon(m.getDungeonCode());
        List<PartySlot> party = m.getParty().stream()
                .map(id -> recruits.findById(id).orElseThrow())
                .map(r -> new PartySlot(r.getId(), r.getSpeciesId(), name(r), r.getLevel(),
                        r.getRecruitedBy() == null ? null : r.getRecruitedBy().getId()))
                .toList();
        Membership leader = m.getLeader();
        return new View(m.getId(), m.getTitle(), m.getKind(), d.code(), d.name(), m.getFloor(),
                catalog.floorLabel(d, m.getFloor()), m.getDifficulty(), m.getClientSpeciesId(),
                m.getClientSpeciesId() == null ? null : catalog.species(m.getClientSpeciesId()).name(),
                m.getRewardPoke(), List.copyOf(m.getRewardItems()), m.getRankPoints(), m.getStatus(),
                leader == null ? null : new Person(leader.getId(), leader.displayName(), leader.getUser().getSpeciesId()), party,
                List.copyOf(m.getBag()), m.getPostedBy().displayName(), m.getPostedAt(), m.getDepartedAt(),
                m.getClosedAt(), m.getOutcomeNote());
    }
}
