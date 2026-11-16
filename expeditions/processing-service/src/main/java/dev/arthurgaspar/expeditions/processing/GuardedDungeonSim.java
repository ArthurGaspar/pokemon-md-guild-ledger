package dev.arthurgaspar.expeditions.processing;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * The simulator behind Resilience4j, configured under {@code resilience4j.*.instances.dungeon-sim}:
 * <ul>
 * <li><b>Retry</b> (outermost): a few quick attempts with exponential backoff and jitter, same idempotency key;</li>
 * <li><b>Circuit breaker</b>: counts every attempt, failed and slow ones; once open, calls fail at once instead of
 * piling onto a simulator that is down, and retry doesn't retry them;</li>
 * <li><b>Bulkhead</b> (innermost): caps concurrent calls, so a slow simulator can't take every worker thread.</li>
 * </ul>
 * When all of that still fails, {@link RunService} schedules another attempt later, up to the run's deadline.
 */
@Component
public class GuardedDungeonSim {

    public static final String NAME = "dungeon-sim";

    private final DungeonSim sim;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public GuardedDungeonSim(DungeonSim sim, RetryRegistry retries, CircuitBreakerRegistry breakers,
                             BulkheadRegistry bulkheads) {
        this.sim = sim;
        this.retry = retries.retry(NAME);
        this.circuitBreaker = breakers.circuitBreaker(NAME);
        this.bulkhead = bulkheads.bulkhead(NAME);
    }

    public DungeonSim.Result run(String runId, long expeditionId, long partyId, List<String> members) {
        Supplier<DungeonSim.Result> call = () -> sim.run(runId, expeditionId, partyId, members);
        call = Bulkhead.decorateSupplier(bulkhead, call);
        call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        call = Retry.decorateSupplier(retry, call);
        return call.get();
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
