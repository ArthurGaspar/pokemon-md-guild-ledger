package dev.arthurgaspar.guildledger.team;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.auth.UserRepository;
import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.ledger.Accounts;
import dev.arthurgaspar.guildledger.ledger.LedgerService;
import dev.arthurgaspar.guildledger.ledger.LedgerService.Leg;
import dev.arthurgaspar.guildledger.ledger.Reason;
import dev.arthurgaspar.guildledger.mission.MissionRepository;
import dev.arthurgaspar.guildledger.recruit.RecruitRepository;

@Service
public class TeamService {

    public record TeamSummary(long id, String name, String rank, int rankPoints, Role role, long memberCount) {}

    public record NextRank(String code, String name, int minPoints) {}

    public record TeamDetail(long id, String name, String rank, String rankName, int rankPoints, NextRank nextRank,
                             String maxDifficulty, int rosterCap, int bagCap, int treasuryCutPct, SplitMode splitMode,
                             long myMembershipId, Role myRole, long treasury, long memberCount, long recruitCount,
                             Instant createdAt) {}

    public record MemberView(long membershipId, long userId, String username, String displayName, int speciesId,
                             Role role, Instant joinedAt, long wallet, long recruits) {}

    private final TeamRepository teams;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final RecruitRepository recruits;
    private final MissionRepository missions;
    private final TeamAccess access;
    private final LedgerService ledger;
    private final EventLog events;
    private final Catalog catalog;
    private final Clock clock;

    TeamService(TeamRepository teams, MembershipRepository memberships, UserRepository users,
                RecruitRepository recruits, MissionRepository missions, TeamAccess access, LedgerService ledger,
                EventLog events, Catalog catalog, Clock clock) {
        this.teams = teams;
        this.memberships = memberships;
        this.users = users;
        this.recruits = recruits;
        this.missions = missions;
        this.access = access;
        this.ledger = ledger;
        this.events = events;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public TeamDetail create(long userId, String rawName) {
        String name = rawName.strip();
        if (teams.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("team.name-taken", "Another team already uses that name");
        }
        Team team = teams.save(new Team(name, clock.instant()));
        var user = users.getReferenceById(userId);
        memberships.save(new Membership(team.getId(), user, Role.LEADER, clock.instant()));
        events.record(team.getId(), userId, "team.created", "Founded " + name);
        return detail(team.getId(), userId);
    }

    @Transactional(readOnly = true)
    public List<TeamSummary> teamsOf(long userId) {
        return memberships.findActiveByUser(userId).stream()
                .map(m -> {
                    Team t = teams.findById(m.getTeamId()).orElseThrow();
                    return new TeamSummary(t.getId(), t.getName(), catalog.rankFor(t.getRankPoints()).code(),
                            t.getRankPoints(), m.getRole(), memberships.countActive(t.getId()));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDetail detail(long teamId, long userId) {
        Membership me = access.member(teamId, userId);
        Team t = teams.findById(teamId).orElseThrow();
        Catalog.Rank rank = catalog.rankFor(t.getRankPoints());
        NextRank next = catalog.nextRank(rank).map(r -> new NextRank(r.code(), r.name(), r.minPoints())).orElse(null);
        return new TeamDetail(t.getId(), t.getName(), rank.code(), rank.name(), t.getRankPoints(), next,
                rank.maxDifficulty(), rank.rosterCap(), rank.bagCap(), t.getTreasuryCutPct(), t.getSplitMode(),
                me.getId(), me.getRole(), ledger.balance(teamId, Accounts.TREASURY, Accounts.POKE),
                memberships.countActive(teamId), recruits.countActive(teamId), t.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(long teamId, long userId) {
        access.member(teamId, userId);
        Map<Long, Long> wallets = ledger.wallets(teamId);
        Map<Long, Long> recruitCounts = recruits.countActiveByRecruiter(teamId).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return memberships.findActive(teamId).stream()
                .map(m -> view(m, wallets.getOrDefault(m.getId(), 0L), recruitCounts.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    private static MemberView view(Membership m, long wallet, long recruits) {
        return new MemberView(m.getId(), m.getUser().getId(), m.getUser().getUsername(), m.displayName(),
                m.getUser().getSpeciesId(), m.getRole(), m.getJoinedAt(), wallet, recruits);
    }

    @Transactional
    public void changeRole(long teamId, long actorId, long membershipId, Role role) {
        Membership actor = access.atLeast(teamId, actorId, Role.LEADER);
        if (role == Role.LEADER) {
            throw ApiException.invalid("role.invalid", "Use leadership transfer to hand over the team");
        }
        Membership target = activeMember(teamId, membershipId);
        if (target.getId().equals(actor.getId())) {
            throw ApiException.invalid("role.invalid", "The leader can't demote themselves; transfer leadership first");
        }
        if (target.getRole() == role) {
            return;
        }
        target.setRole(role);
        events.record(teamId, actorId, "member.role",
                target.displayName() + " is now " + role.name().toLowerCase());
    }

    @Transactional
    public void transferLeadership(long teamId, long actorId, long membershipId) {
        Membership actor = access.atLeast(teamId, actorId, Role.LEADER);
        Membership target = activeMember(teamId, membershipId);
        if (target.getId().equals(actor.getId())) {
            return;
        }
        actor.setRole(Role.OFFICER);
        target.setRole(Role.LEADER);
        events.record(teamId, actorId, "member.leader", target.displayName() + " now leads the team");
    }

    /**
     * Removes a member, or lets someone leave when they target their own membership. The leader can remove
     * anyone else; officers can remove plain members. Whatever is left in the wallet is cashed out.
     */
    @Transactional
    public void removeMember(long teamId, long actorId, long membershipId) {
        teams.lockById(teamId);
        Membership actor = access.member(teamId, actorId);
        Membership target = activeMember(teamId, membershipId);
        boolean leaving = target.getId().equals(actor.getId());
        if (leaving && actor.getRole() == Role.LEADER) {
            throw ApiException.invalid("member.leader-cannot-leave", "Transfer leadership before leaving the team");
        }
        if (!leaving) {
            boolean allowed = actor.getRole() == Role.LEADER
                    || (actor.getRole() == Role.OFFICER && target.getRole() == Role.MEMBER);
            if (!allowed) {
                throw ApiException.forbidden("You can't remove this member");
            }
        }
        if (missions.countOpenLedBy(target.getId()) > 0) {
            throw ApiException.conflict("member.on-mission",
                    target.displayName() + " leads a planned or departed mission; reassign or close it first");
        }
        long wallet = ledger.balance(teamId, Accounts.wallet(target.getId()), Accounts.POKE);
        if (wallet > 0) {
            ledger.post(teamId, actorId, Reason.CASH_OUT, null, target.displayName() + " left the team",
                    Leg.transfer(Accounts.wallet(target.getId()), Accounts.WORLD, Accounts.POKE, wallet));
        }
        target.leave(clock.instant());
        events.record(teamId, actorId, leaving ? "member.left" : "member.removed",
                leaving ? target.displayName() + " left the team"
                        : actor.displayName() + " removed " + target.displayName());
    }

    @Transactional
    public TeamDetail updatePolicy(long teamId, long actorId, int treasuryCutPct, SplitMode splitMode) {
        access.atLeast(teamId, actorId, Role.LEADER);
        Team team = teams.findById(teamId).orElseThrow();
        if (team.getTreasuryCutPct() != treasuryCutPct || team.getSplitMode() != splitMode) {
            team.setPolicy(treasuryCutPct, splitMode);
            events.record(teamId, actorId, "team.policy", "Reward policy: " + treasuryCutPct + "% to treasury, "
                    + (splitMode == SplitMode.EQUAL ? "equal split" : "split by party slots"));
        }
        return detail(teamId, actorId);
    }

    @Transactional(readOnly = true)
    public List<EventLog.View> events(long teamId, long userId, int limit) {
        access.member(teamId, userId);
        return events.recent(teamId, limit);
    }

    private Membership activeMember(long teamId, long membershipId) {
        return memberships.findById(membershipId)
                .filter(m -> m.getTeamId() == teamId && m.isActive())
                .orElseThrow(() -> ApiException.notFound("member.not-found", "No such member in this team"));
    }
}
