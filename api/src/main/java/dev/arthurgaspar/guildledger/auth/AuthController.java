package dev.arthurgaspar.guildledger.auth;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.invite.InvitationService;
import dev.arthurgaspar.guildledger.team.TeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
class AuthController {

    record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "must be 3-20 letters, digits or _") String username,
            @NotBlank @Size(max = 40) String displayName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Integer speciesId) {}

    record ProfileRequest(@NotBlank @Size(max = 40) String displayName, @NotNull Integer speciesId) {}

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    record AuthResponse(String token, UserView user) {}

    record Me(UserView user, java.util.List<TeamService.TeamSummary> teams, long pendingInvitations) {}

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final TeamService teams;
    private final InvitationService invitations;
    private final Catalog catalog;
    private final Clock clock;

    AuthController(UserRepository users, PasswordEncoder passwords, TokenService tokens, TeamService teams,
                   InvitationService invitations, Catalog catalog, Clock clock) {
        this.catalog = catalog;
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.teams = teams;
        this.invitations = invitations;
        this.clock = clock;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        catalog.species(req.speciesId());
        if (users.existsByUsernameIgnoreCase(req.username())) {
            throw ApiException.conflict("auth.username-taken", "That username is already registered");
        }
        User user = users.save(new User(req.username(), req.displayName().strip(), req.speciesId(),
                passwords.encode(req.password()), clock.instant()));
        return new AuthResponse(tokens.issue(user), UserView.of(user));
    }

    @PostMapping("/auth/login")
    AuthResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByUsernameIgnoreCase(req.username())
                .filter(u -> passwords.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "auth.bad-credentials",
                        "Username or password is wrong"));
        return new AuthResponse(tokens.issue(user), UserView.of(user));
    }

    @PatchMapping("/me")
    @Transactional
    UserView updateProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest req) {
        catalog.species(req.speciesId());
        User user = users.findById(CurrentUser.id(jwt)).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "auth.unknown-user", "This account no longer exists"));
        user.setProfile(req.displayName().strip(), req.speciesId());
        return UserView.of(user);
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    Me me(@AuthenticationPrincipal Jwt jwt) {
        long userId = CurrentUser.id(jwt);
        User user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "auth.unknown-user", "This account no longer exists"));
        return new Me(UserView.of(user), teams.teamsOf(userId), invitations.pendingCount(userId));
    }
}
