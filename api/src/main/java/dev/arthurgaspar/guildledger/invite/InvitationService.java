package dev.arthurgaspar.guildledger.invite;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.auth.UserRepository;
import dev.arthurgaspar.guildledger.auth.UserView;
import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.team.EventLog;
import dev.arthurgaspar.guildledger.team.Membership;
import dev.arthurgaspar.guildledger.team.MembershipRepository;
import dev.arthurgaspar.guildledger.team.Role;
import dev.arthurgaspar.guildledger.team.Team;
import dev.arthurgaspar.guildledger.team.TeamAccess;
import dev.arthurgaspar.guildledger.team.TeamRepository;

@Service
public class InvitationService {

    public record View(long id, long teamId, String teamName, UserView invitee, UserView invitedBy, Role role,
                       Invitation.Status status, Instant createdAt, Instant expiresAt) {}

    private final InvitationRepository invitations;
    private final TeamRepository teams;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final TeamAccess access;
    private final EventLog events;
    private final Catalog catalog;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, TeamRepository teams, MembershipRepository memberships,
                      UserRepository users, TeamAccess access, EventLog events, Catalog catalog, Clock clock) {
        this.invitations = invitations;
        this.teams = teams;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.events = events;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public View invite(long teamId, long actorId, String username, Role role) {
        Membership actor = access.atLeast(teamId, actorId, Role.OFFICER);
        if (role == Role.LEADER) {
            throw ApiException.invalid("role.invalid", "Invite as member or officer; leadership is transferred");
        }
        if (role == Role.OFFICER && actor.getRole() != Role.LEADER) {
            throw ApiException.forbidden("Only the leader can invite officers");
        }
        var invitee = users.findByUsernameIgnoreCase(username.strip())
                .orElseThrow(() -> ApiException.notFound("invite.user-not-found", "No user called " + username));
        Instant now = clock.instant();
        if (memberships.findActive(teamId, invitee.getId()).isPresent()) {
            throw ApiException.conflict("invite.already-member", invitee.getDisplayName() + " is already on the team");
        }
        if (invitations.existsOpen(teamId, invitee.getId(), now)) {
            throw ApiException.conflict("invite.pending-exists", invitee.getDisplayName() + " already has a pending invitation");
        }
        requireRoom(teamId);
        Invitation inv = invitations.save(new Invitation(teamId, invitee, actor.getUser(), role, now,
                now.plus(Duration.ofDays(catalog.limits().invitationDays()))));
        events.record(teamId, actorId, "invite.sent",
                actor.displayName() + " invited " + invitee.getDisplayName() + " as " + role.name().toLowerCase());
        return view(inv, now);
    }

    @Transactional(readOnly = true)
    public List<View> forTeam(long teamId, long actorId) {
        access.atLeast(teamId, actorId, Role.OFFICER);
        Instant now = clock.instant();
        return invitations.findByTeam(teamId, Limit.of(50)).stream().map(i -> view(i, now)).toList();
    }

    @Transactional
    public void revoke(long teamId, long actorId, long invitationId) {
        access.atLeast(teamId, actorId, Role.OFFICER);
        Invitation inv = invitations.findById(invitationId)
                .filter(i -> i.getTeamId() == teamId)
                .orElseThrow(() -> ApiException.notFound("invite.not-found", "Invitation not found"));
        requirePending(inv);
        inv.close(Invitation.Status.REVOKED, clock.instant());
        events.record(teamId, actorId, "invite.revoked", "Invitation for " + inv.getInvitee().getDisplayName() + " revoked");
    }

    @Transactional(readOnly = true)
    public List<View> mine(long userId) {
        Instant now = clock.instant();
        return invitations.findOpenFor(userId, now).stream().map(i -> view(i, now)).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount(long userId) {
        return invitations.countOpenFor(userId, clock.instant());
    }

    @Transactional
    public long accept(long userId, long invitationId) {
        Invitation inv = ownInvitation(userId, invitationId);
        requirePending(inv);
        teams.lockById(inv.getTeamId());
        if (memberships.findActive(inv.getTeamId(), userId).isPresent()) {
            inv.close(Invitation.Status.ACCEPTED, clock.instant());
            return inv.getTeamId();
        }
        requireRoom(inv.getTeamId());
        memberships.save(new Membership(inv.getTeamId(), inv.getInvitee(), inv.getRole(), clock.instant()));
        inv.close(Invitation.Status.ACCEPTED, clock.instant());
        events.record(inv.getTeamId(), userId, "member.joined", inv.getInvitee().getDisplayName() + " joined the team");
        return inv.getTeamId();
    }

    @Transactional
    public void decline(long userId, long invitationId) {
        Invitation inv = ownInvitation(userId, invitationId);
        requirePending(inv);
        inv.close(Invitation.Status.DECLINED, clock.instant());
        events.record(inv.getTeamId(), userId, "invite.declined", inv.getInvitee().getDisplayName() + " declined the invitation");
    }

    private Invitation ownInvitation(long userId, long invitationId) {
        return invitations.findById(invitationId)
                .filter(i -> i.getInvitee().getId() == userId)
                .orElseThrow(() -> ApiException.notFound("invite.not-found", "Invitation not found"));
    }

    private void requirePending(Invitation inv) {
        Instant now = clock.instant();
        Invitation.Status status = inv.statusAt(now);
        if (status == Invitation.Status.EXPIRED) {
            throw ApiException.conflict("invite.expired", "This invitation expired");
        }
        if (status != Invitation.Status.PENDING) {
            throw ApiException.conflict("invite.not-pending", "This invitation was already " + status.name().toLowerCase());
        }
    }

    private void requireRoom(long teamId) {
        if (memberships.countActive(teamId) >= catalog.limits().teamMembers()) {
            throw ApiException.conflict("team.full", "Teams are capped at " + catalog.limits().teamMembers() + " members");
        }
    }

    private View view(Invitation i, Instant now) {
        Team team = teams.findById(i.getTeamId()).orElseThrow();
        return new View(i.getId(), team.getId(), team.getName(), UserView.of(i.getInvitee()),
                UserView.of(i.getInvitedBy()), i.getRole(), i.statusAt(now), i.getCreatedAt(), i.getExpiresAt());
    }
}
