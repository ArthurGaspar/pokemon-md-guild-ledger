package dev.arthurgaspar.guildledger.recruit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.mission.MissionRepository;
import dev.arthurgaspar.guildledger.team.EventLog;
import dev.arthurgaspar.guildledger.team.Membership;
import dev.arthurgaspar.guildledger.team.Role;
import dev.arthurgaspar.guildledger.team.Team;
import dev.arthurgaspar.guildledger.team.TeamAccess;
import dev.arthurgaspar.guildledger.team.TeamRepository;

@Service
public class RecruitService {

    public record Recruiter(long membershipId, String displayName) {}

    public record View(long id, int speciesId, String speciesName, List<String> types, String nickname, int level,
                       int exp, int levelExp, Integer nextLevelExp, Recruit.Status status, Recruiter recruitedBy,
                       Instant recruitedAt, Long missionId) {}

    private final RecruitRepository recruits;
    private final TeamRepository teams;
    private final MissionRepository missions;
    private final TeamAccess access;
    private final EventLog events;
    private final Catalog catalog;
    private final Clock clock;

    RecruitService(RecruitRepository recruits, TeamRepository teams, MissionRepository missions, TeamAccess access,
                   EventLog events, Catalog catalog, Clock clock) {
        this.recruits = recruits;
        this.teams = teams;
        this.missions = missions;
        this.access = access;
        this.events = events;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list(long teamId, long userId) {
        access.member(teamId, userId);
        return recruits.findActive(teamId).stream().map(this::view).toList();
    }

    @Transactional
    public View recruit(long teamId, long userId, int speciesId, String nickname, int level) {
        Membership me = access.member(teamId, userId);
        Catalog.Species species = catalog.species(speciesId);
        Team team = teams.lockById(teamId).orElseThrow();
        Catalog.Rank rank = catalog.rankFor(team.getRankPoints());
        if (recruits.countActive(teamId) >= rank.rosterCap()) {
            throw ApiException.conflict("recruit.roster-full",
                    rank.name() + " Rank teams can keep " + rank.rosterCap() + " recruits. Release one or rank up.");
        }
        Recruit r = recruits.save(new Recruit(teamId, speciesId, clean(nickname), level, me, clock.instant()));
        events.record(teamId, userId, "recruit.joined",
                me.displayName() + " recruited " + label(r, species) + " (Lv. " + level + ")");
        return view(r);
    }

    @Transactional
    public View rename(long teamId, long userId, long recruitId, String nickname) {
        Recruit r = editable(teamId, userId, recruitId);
        r.setNickname(clean(nickname));
        return view(r);
    }

    @Transactional
    public void release(long teamId, long userId, long recruitId) {
        Recruit r = editable(teamId, userId, recruitId);
        if (r.getStatus() == Recruit.Status.ON_MISSION || missions.countPlannedWithRecruit(recruitId) > 0) {
            throw ApiException.conflict("recruit.busy", "This recruit is assigned to a mission");
        }
        r.release();
        events.record(teamId, userId, "recruit.released", label(r, catalog.species(r.getSpeciesId())) + " was released");
    }

    /** The recruiter can edit their own recruits; officers and the leader can edit any. */
    private Recruit editable(long teamId, long userId, long recruitId) {
        Membership me = access.member(teamId, userId);
        Recruit r = recruits.findById(recruitId)
                .filter(x -> x.getTeamId() == teamId && x.getStatus() != Recruit.Status.RELEASED)
                .orElseThrow(() -> ApiException.notFound("recruit.not-found", "Recruit not found"));
        boolean own = r.getRecruitedBy() != null && r.getRecruitedBy().getId().equals(me.getId());
        if (!own && !me.getRole().atLeast(Role.OFFICER)) {
            throw ApiException.forbidden("Only the recruiter or an officer can change this recruit");
        }
        return r;
    }

    private static String clean(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        return nickname.strip();
    }

    public static String label(Recruit r, Catalog.Species species) {
        return r.getNickname() == null ? species.name() : r.getNickname() + " the " + species.name();
    }

    View view(Recruit r) {
        Catalog.Species s = catalog.species(r.getSpeciesId());
        int max = catalog.limits().maxLevel();
        Membership by = r.getRecruitedBy();
        return new View(r.getId(), s.id(), s.name(), s.types(), r.getNickname(), r.getLevel(), r.getExp(),
                Leveling.expForLevel(r.getLevel()), r.getLevel() >= max ? null : Leveling.expForLevel(r.getLevel() + 1),
                r.getStatus(), by == null ? null : new Recruiter(by.getId(), by.displayName()), r.getRecruitedAt(),
                r.getMissionId());
    }
}
