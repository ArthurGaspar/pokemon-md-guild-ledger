package dev.arthurgaspar.guildledger.invite;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.auth.CurrentUser;
import dev.arthurgaspar.guildledger.team.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
class InvitationController {

    record InviteRequest(@NotBlank String username, @NotNull Role role) {}

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/teams/{teamId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationService.View invite(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                                  @Valid @RequestBody InviteRequest req) {
        return invitations.invite(teamId, CurrentUser.id(jwt), req.username(), req.role());
    }

    @GetMapping("/teams/{teamId}/invitations")
    List<InvitationService.View> forTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return invitations.forTeam(teamId, CurrentUser.id(jwt));
    }

    @DeleteMapping("/teams/{teamId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long invitationId) {
        invitations.revoke(teamId, CurrentUser.id(jwt), invitationId);
    }

    @GetMapping("/invitations")
    List<InvitationService.View> mine(@AuthenticationPrincipal Jwt jwt) {
        return invitations.mine(CurrentUser.id(jwt));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    Map<String, Long> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long invitationId) {
        return Map.of("teamId", invitations.accept(CurrentUser.id(jwt), invitationId));
    }

    @PostMapping("/invitations/{invitationId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable long invitationId) {
        invitations.decline(CurrentUser.id(jwt), invitationId);
    }
}
