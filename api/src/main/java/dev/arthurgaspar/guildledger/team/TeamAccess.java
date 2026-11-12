package dev.arthurgaspar.guildledger.team;

import org.springframework.stereotype.Component;

import dev.arthurgaspar.guildledger.common.ApiException;

/**
 * Team-scoped authorization. Non-members get 404 rather than 403 so team ids can't be probed;
 * members without enough authority get 403.
 */
@Component
public class TeamAccess {

    private final MembershipRepository memberships;

    TeamAccess(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Membership member(long teamId, long userId) {
        return memberships.findActive(teamId, userId)
                .orElseThrow(() -> ApiException.notFound("team.not-found", "Team not found"));
    }

    public Membership atLeast(long teamId, long userId, Role role) {
        Membership m = member(teamId, userId);
        if (!m.getRole().atLeast(role)) {
            throw ApiException.forbidden("Only a team " + role.name().toLowerCase() + " can do this");
        }
        return m;
    }
}
