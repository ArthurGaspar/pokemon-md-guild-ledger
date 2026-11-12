package dev.arthurgaspar.guildledger.mission;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.auth.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/teams/{teamId}/missions")
class MissionController {

    private final MissionService missions;

    MissionController(MissionService missions) {
        this.missions = missions;
    }

    @GetMapping
    List<MissionService.View> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return missions.list(teamId, CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MissionService.View post(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                             @Valid @RequestBody MissionService.PostRequest req) {
        return missions.post(teamId, CurrentUser.id(jwt), req);
    }

    @GetMapping("/{missionId}")
    MissionService.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long missionId) {
        return missions.get(teamId, CurrentUser.id(jwt), missionId);
    }

    @PutMapping("/{missionId}/plan")
    MissionService.View plan(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long missionId,
                             @Valid @RequestBody MissionService.PlanRequest req) {
        return missions.plan(teamId, CurrentUser.id(jwt), missionId, req);
    }

    @PostMapping("/{missionId}/depart")
    MissionService.View depart(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long missionId) {
        return missions.depart(teamId, CurrentUser.id(jwt), missionId);
    }

    @GetMapping("/{missionId}/payout-preview")
    MissionService.Preview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                                   @PathVariable long missionId) {
        return missions.preview(teamId, CurrentUser.id(jwt), missionId);
    }

    @PostMapping("/{missionId}/report")
    MissionService.View report(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
                               @PathVariable long missionId, @Valid @RequestBody MissionService.ReportRequest req) {
        return missions.report(teamId, CurrentUser.id(jwt), missionId, req);
    }

    @PostMapping("/{missionId}/cancel")
    MissionService.View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @PathVariable long missionId) {
        return missions.cancel(teamId, CurrentUser.id(jwt), missionId);
    }
}
