package dev.arthurgaspar.expeditions.catalog;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.Role;

/**
 * An empty catalog gets one rare expedition (25 parties of four) opening shortly after startup. It goes through the
 * service, so it is published to Kafka like any other.
 */
@Component
@ConditionalOnProperty(name = "catalog.seed.enabled", havingValue = "true")
public class SeedExpeditions implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedExpeditions.class);

    private final ExpeditionRepository expeditions;
    private final ExpeditionService service;
    private final Clock clock;
    private final Duration opensIn;

    public SeedExpeditions(ExpeditionRepository expeditions, ExpeditionService service, Clock clock,
                           @Value("${catalog.seed.opens-in:PT1M}") Duration opensIn) {
        this.expeditions = expeditions;
        this.service = service;
        this.clock = clock;
        this.opensIn = opensIn;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (expeditions.count() > 0) {
            return;
        }
        var draft = new ExpeditionService.Draft("sky-peak-grand", "Sky Peak: the Guild's Grand Expedition",
                "sky-peak", "STAR", clock.instant().plus(opensIn), 25,
                Map.of(Role.TANK, 1, Role.HEALER, 1, Role.DAMAGE, 2));
        var view = service.publish("guild", draft);
        log.info("Seeded expedition {} ({} slots), opening at {}", view.code(), view.totalSlots(), view.opensAt());
    }
}
