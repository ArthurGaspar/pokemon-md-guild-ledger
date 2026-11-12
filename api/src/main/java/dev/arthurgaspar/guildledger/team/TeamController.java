package dev.arthurgaspar.guildledger.team;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/teams")
class TeamController {

    record CreateTeam(@NotBlank @Size(min = 3, max = 40) String name) {}

    record ChangeRole(@NotNull Role role) {}

    record TransferLeadership(@NotNull Long membershipId) {}

    record Policy(@Min(0) @Max(100) int treasuryCutPct, @NotNull SplitMode splitMode) {}

    private final TeamService teams;

    TeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping
    List<TeamService.TeamSummary> mine(@AuthenticationPrincipal Jwt jwt) {
        return teams.teamsOf(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TeamService.TeamDetail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTeam req) {
        return teams.create(CurrentUser.id(jwt), req.name());
    }

    @GetMapping("/{teamId}")
    TeamService.TeamDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return teams.detail(teamId, CurrentUser.id(jwt));
    }

    @PutMapping("/{teamId}/policy")
    TeamService.TeamDetail policy(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                                  @Valid @RequestBody Policy req) {
        return teams.updatePolicy(teamId, CurrentUser.id(jwt), req.treasuryCutPct(), req.splitMode());
    }

    @GetMapping("/{teamId}/members")
    List<TeamService.MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return teams.members(teamId, CurrentUser.id(jwt));
    }

    @PatchMapping("/{teamId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long membershipId,
                    @Valid @RequestBody ChangeRole req) {
        teams.changeRole(teamId, CurrentUser.id(jwt), membershipId, req.role());
    }

    @DeleteMapping("/{teamId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long membershipId) {
        teams.removeMember(teamId, CurrentUser.id(jwt), membershipId);
    }

    @PostMapping("/{teamId}/leadership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                  @Valid @RequestBody TransferLeadership req) {
        teams.transferLeadership(teamId, CurrentUser.id(jwt), req.membershipId());
    }

    @GetMapping("/{teamId}/events")
    List<EventLog.View> events(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                               @RequestParam(defaultValue = "40") int limit) {
        return teams.events(teamId, CurrentUser.id(jwt), limit);
    }
}
