package dev.arthurgaspar.expeditions.processing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Events.RunCompleted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailed;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;
import dev.arthurgaspar.expeditions.platform.events.Topics;
import dev.arthurgaspar.expeditions.platform.outbox.Inbox;
import dev.arthurgaspar.expeditions.platform.outbox.Outbox;
import dev.arthurgaspar.expeditions.platform.tracing.TraceContext;
import dev.arthurgaspar.expeditions.platform.web.ApiException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns {@code party.locked} into a run and the simulator's answer into {@code run.completed} or {@code run.failed}.
 * The call itself happens outside any transaction; only recording its result is transactional.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final TypeReference<List<Member>> MEMBERS = new TypeReference<>() {};

    private final RunRepository runs;
    private final GuardedDungeonSim sim;
    private final Inbox inbox;
    private final Outbox outbox;
    private final ProcessingSettings settings;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TraceContext traces;

    public RunService(RunRepository runs, GuardedDungeonSim sim, Inbox inbox, Outbox outbox, ProcessingSettings settings,
                      JsonMapper json, Clock clock, PlatformTransactionManager transactions,
                      ObjectProvider<TraceContext> traces) {
        this.traces = traces.getIfAvailable();
        this.runs = runs;
        this.sim = sim;
        this.inbox = inbox;
        this.outbox = outbox;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** A party can lock more than once (after an aborted run), so redeliveries are caught by event id, not party. */
    @Transactional
    public void start(EventEnvelope envelope, PartyLocked party) {
        if (!inbox.firstDelivery(envelope)) {
            return;
        }
        Instant now = clock.instant();
        runs.save(new Run(UUID.randomUUID().toString(), party.expeditionId(), party.partyId(),
                json.writeValueAsString(party.members()), now, now.plus(settings.runDeadline()),
                traces == null ? null : traces.current()));
    }

    /** Leases due runs to this worker. */
    @Transactional
    public List<String> claimDue() {
        Instant now = clock.instant();
        return runs.due(now, settings.batch()).stream()
                .filter(id -> runs.claim(id, now, now.plus(settings.lease())))
                .toList();
    }

    /** One attempt at a claimed run, in the run's trace. Never throws: every outcome is recorded on the run. */
    public void attempt(String runId) {
        Run run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != Run.Status.PENDING) {
            return;
        }
        if (traces == null) {
            attempt(run);
        } else {
            traces.inSpan("run attempt", run.getTraceParent(), () -> attempt(run));
        }
    }

    private void attempt(Run run) {
        String runId = run.getId();
        List<Member> members = members(run);
        DungeonSim.Result result;
        try {
            result = sim.run(run.getId(), run.getExpeditionId(), run.getPartyId(),
                    members.stream().map(Member::adventurerId).toList());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> attemptFailed(runId, describe(e)));
            return;
        }
        tx.executeWithoutResult(status -> answered(runId, result));
    }

    private void answered(String runId, DungeonSim.Result result) {
        Run run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != Run.Status.PENDING) {
            return;
        }
        Instant now = clock.instant();
        List<Member> members = members(run);
        if (result.outcome() == DungeonSim.Outcome.CLEARED) {
            run.cleared(result.floorsCleared(), result.pokeFound(), now);
            emit(run, Events.RUN_COMPLETED, new RunCompleted(run.getId(), run.getExpeditionId(), run.getPartyId(),
                    members, result.floorsCleared(), result.pokeFound()));
        } else {
            run.fainted(result.floorsCleared(), result.pokeFound(), now);
            emit(run, Events.RUN_FAILED, new RunFailed(run.getId(), run.getExpeditionId(), run.getPartyId(), members,
                    RunFailure.FAINTED, "Fainted on floor " + (result.floorsCleared() + 1) + " of " + result.floors()));
        }
    }

    private void attemptFailed(String runId, String error) {
        Run run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != Run.Status.PENDING) {
            return;
        }
        Instant now = clock.instant();
        if (run.attemptFailed(error, now, backoff(run.getAttempts() + 1))) {
            log.info("Run {} attempt {} failed ({}); next try at {}", runId, run.getAttempts(), error, run.getNextAttemptAt());
            return;
        }
        run.unavailable(now);
        log.warn("Run {} gave up after {} attempts: {}", runId, run.getAttempts(), error);
        emit(run, Events.RUN_FAILED, new RunFailed(run.getId(), run.getExpeditionId(), run.getPartyId(), members(run),
                RunFailure.UNAVAILABLE, "No answer from the dungeon after " + run.getAttempts() + " attempts: " + error));
    }

    /** Doubles from {@code retryBase} up to {@code retryMax}. */
    Duration backoff(int attempt) {
        Duration d = settings.retryBase().multipliedBy(1L << Math.min(attempt - 1, 20));
        return d.compareTo(settings.retryMax()) > 0 ? settings.retryMax() : d;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof CallNotPermittedException) {
            return "circuit open";
        }
        if (e instanceof BulkheadFullException) {
            return "too many runs in flight";
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Transactional(readOnly = true)
    public Run get(String id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("run.not-found", "Run not found"));
    }

    @Transactional(readOnly = true)
    public List<Run> forParty(long expeditionId, long partyId) {
        return runs.findByExpeditionIdAndPartyIdOrderByCreatedAt(expeditionId, partyId);
    }

    List<Member> members(Run run) {
        return json.readValue(run.getMembers(), MEMBERS);
    }

    private void emit(Run run, String type, Object payload) {
        outbox.append(Topics.RUNS, run.getExpeditionId().toString(), type, payload);
    }
}
