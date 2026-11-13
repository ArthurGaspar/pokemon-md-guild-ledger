package dev.arthurgaspar.expeditions.queue;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lets people through, every second, as far as open slots allow. Safe to run on several instances at once: the
 * script caps admissions by open slots atomically, so extra instances only make admission happen sooner.
 */
@Component
@ConditionalOnProperty(name = "queue.admitter-enabled", havingValue = "true", matchIfMissing = true)
public class Admitter {

    private static final Logger log = LoggerFactory.getLogger(Admitter.class);

    private final QueueStore store;
    private final QueueSettings settings;
    private final Clock clock;

    public Admitter(QueueStore store, QueueSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${queue.admit-interval:1s}")
    public void admit() {
        for (long expeditionId : store.expeditions()) {
            try {
                List<String> through = store.admit(expeditionId, clock.instant(), settings.batch(),
                        settings.admissionTtl().toMillis());
                if (!through.isEmpty()) {
                    log.info("Admitted {} to expedition {}", through.size(), expeditionId);
                }
            } catch (RuntimeException e) {
                log.warn("Admitting to expedition {} failed: {}", expeditionId, e.getMessage());
            }
        }
    }
}
