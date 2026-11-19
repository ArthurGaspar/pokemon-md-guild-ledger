package dev.arthurgaspar.expeditions.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Time-driven transitions: lapsed holds and missed ready checks. Each expedition is swept in its own transaction. */
@Component
@ConditionalOnProperty(name = "reservation.sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final ReservationService service;

    public Sweeper(ReservationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${reservation.sweep-interval:1s}")
    public void sweep() {
        for (long expeditionId : service.expeditionsWithWorkDue()) {
            try {
                service.sweep(expeditionId);
            } catch (RuntimeException e) {
                log.warn("Sweeping expedition {} failed; retrying next run: {}", expeditionId, e.getMessage());
            }
        }
    }
}
