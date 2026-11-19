package dev.arthurgaspar.expeditions.processing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Polls for due runs and attempts each on its own virtual thread. How many reach the simulator at once is the
 * bulkhead's call, not this pool's.
 */
@Component
@ConditionalOnProperty(name = "processing.worker-enabled", havingValue = "true", matchIfMissing = true)
public class RunWorker {

    private final RunService service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RunWorker(RunService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${processing.poll-interval:500ms}")
    public void poll() {
        for (String runId : service.claimDue()) {
            executor.submit(() -> service.attempt(runId));
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
