package dev.arthurgaspar.guildledger.recruit;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/teams/{teamId}/recruits")
class RecruitController {

    record RecruitRequest(int speciesId, @Size(max = 20) String nickname, @Min(1) @Max(100) int level) {}

    record RenameRequest(@Size(max = 20) String nickname) {}

    private final RecruitService recruits;

    RecruitController(RecruitService recruits) {
        this.recruits = recruits;
    }

    @GetMapping
    List<RecruitService.View> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return recruits.list(teamId, CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecruitService.View recruit(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                                @Valid @RequestBody RecruitRequest req) {
        return recruits.recruit(teamId, CurrentUser.id(jwt), req.speciesId(), req.nickname(), req.level());
    }

    @PatchMapping("/{recruitId}")
    RecruitService.View rename(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                               @PathVariable long recruitId, @Valid @RequestBody RenameRequest req) {
        return recruits.rename(teamId, CurrentUser.id(jwt), recruitId, req.nickname());
    }

    @DeleteMapping("/{recruitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long recruitId) {
        recruits.release(teamId, CurrentUser.id(jwt), recruitId);
    }
}
