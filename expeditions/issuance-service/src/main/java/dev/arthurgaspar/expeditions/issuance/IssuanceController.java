package dev.arthurgaspar.expeditions.issuance;

import java.time.Instant;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.web.Adventurer;

@RestController
@RequestMapping("/api")
public class IssuanceController {

    public record CertificateView(long id, String runId, long expeditionId, long partyId, Role role, int floorsCleared,
                                  int pokeShare, Instant issuedAt) {}

    private final IssuanceService service;
    private final NotificationHub hub;

    public IssuanceController(IssuanceService service, NotificationHub hub) {
        this.service = service;
        this.hub = hub;
    }

    @GetMapping("/certificates")
    List<CertificateView> certificates(Adventurer me) {
        return service.certificates(me.id()).stream()
                .map(c -> new CertificateView(c.getId(), c.getRunId(), c.getExpeditionId(), c.getPartyId(), c.getRole(),
                        c.getFloorsCleared(), c.getPokeShare(), c.getIssuedAt()))
                .toList();
    }

    /** The latest 50, newest first; {@code after} picks up from the last one seen. */
    @GetMapping("/notifications")
    List<NotificationHub.View> notifications(Adventurer me, @RequestParam(defaultValue = "0") long after) {
        return service.notifications(me.id(), after).stream().map(NotificationHub.View::of).toList();
    }

    /** Server-sent events, one per notification, named by kind. */
    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(Adventurer me) {
        return hub.subscribe(me.id());
    }
}
