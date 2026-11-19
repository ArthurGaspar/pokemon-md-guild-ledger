package dev.arthurgaspar.expeditions.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpServerErrorException;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Events.RunCompleted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailed;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@SpringBootTest
@ActiveProfiles("test")
class RunServiceTest {

    /** Answers from a script, one entry per call; an empty script means the simulator is down. */
    static class ScriptedSim implements DungeonSim {
        final Deque<Supplier<Result>> script = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public synchronized Result run(String runId, long expeditionId, long partyId, List<String> members) {
            calls.add(runId);
            Supplier<Result> next = script.poll();
            if (next == null) {
                throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return next.get();
        }

        void answer(Outcome outcome, int floorsCleared, int poke) {
            script.add(() -> new Result("x", outcome, 20, floorsCleared, poke));
        }

        void fail(int times) {
            for (int i = 0; i < times; i++) {
                script.add(() -> {
                    throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
                });
            }
        }
    }

    static class TestClock extends Clock {
        volatile Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ScriptedSim scriptedSim() {
            return new ScriptedSim();
        }

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private static final AtomicLong PARTIES = new AtomicLong();

    @Autowired
    RunService service;

    @Autowired
    ScriptedSim sim;

    @Autowired
    TestClock clock;

    @Autowired
    EventCodec codec;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        sim.script.clear();
        sim.calls.clear();
        breakers.circuitBreaker(GuardedDungeonSim.NAME).reset();
        // runs left over from other tests shouldn't be picked up here
        jdbc.update("update runs set status = 'FAILED' where status = 'PENDING'");
    }

    private long lockParty() {
        long partyId = PARTIES.incrementAndGet();
        PartyLocked locked = new PartyLocked(7, partyId, List.of(
                new Member(1, "tank", Role.TANK), new Member(2, "healer", Role.HEALER),
                new Member(3, "dps-1", Role.DAMAGE), new Member(4, "dps-2", Role.DAMAGE)));
        service.start(codec.envelope(Events.PARTY_LOCKED, "7", locked), locked);
        return partyId;
    }

    private Run attemptOnly(long partyId) {
        List<String> claimed = service.claimDue();
        assertThat(claimed).hasSize(1);
        service.attempt(claimed.getFirst());
        return service.forParty(7, partyId).getFirst();
    }

    private <T> T lastEvent(String type, Class<T> payload) {
        String message = jdbc.queryForObject(
                "select payload from outbox where event_type = ? order by id desc limit 1", String.class, type);
        return codec.payload(codec.read(message), payload);
    }

    @Test
    void aClearedRunCompletesWithTheLoot() {
        long party = lockParty();
        sim.answer(DungeonSim.Outcome.CLEARED, 20, 2400);

        Run run = attemptOnly(party);

        assertThat(run.getStatus()).isEqualTo(Run.Status.COMPLETED);
        RunCompleted event = lastEvent(Events.RUN_COMPLETED, RunCompleted.class);
        assertThat(event.runId()).isEqualTo(run.getId());
        assertThat(event.pokeFound()).isEqualTo(2400);
        assertThat(event.members()).extracting(Member::adventurerId).containsExactly("tank", "healer", "dps-1", "dps-2");
    }

    @Test
    void faintingIsAFailedRunNotAnError() {
        long party = lockParty();
        sim.answer(DungeonSim.Outcome.FAINTED, 11, 90);

        Run run = attemptOnly(party);

        assertThat(run.getStatus()).isEqualTo(Run.Status.FAILED);
        assertThat(run.getFailure()).isEqualTo(RunFailure.FAINTED);
        RunFailed event = lastEvent(Events.RUN_FAILED, RunFailed.class);
        assertThat(event.reason()).isEqualTo(RunFailure.FAINTED);
        assertThat(event.detail()).isEqualTo("Fainted on floor 12 of 20");
    }

    @Test
    void quickRetriesAbsorbAFlakyCallWithTheSameKey() {
        long party = lockParty();
        sim.fail(2);
        sim.answer(DungeonSim.Outcome.CLEARED, 20, 1000);

        Run run = attemptOnly(party);

        assertThat(run.getStatus()).isEqualTo(Run.Status.COMPLETED);
        assertThat(sim.calls).hasSize(3).containsOnly(run.getId());
    }

    @Test
    void aDownSimulatorIsRetriedLaterUntilTheDeadline() {
        long party = lockParty();
        Run first = attemptOnly(party);
        assertThat(first.getStatus()).isEqualTo(Run.Status.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(clock.now.plusSeconds(1));
        assertThat(service.claimDue()).isEmpty(); // not due yet

        clock.now = clock.now.plusSeconds(1);
        Run second = attemptOnly(party);
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(second.getNextAttemptAt()).isEqualTo(clock.now.plusSeconds(2));

        clock.now = first.getDeadline();
        Run last = attemptOnly(party);
        assertThat(last.getStatus()).isEqualTo(Run.Status.FAILED);
        assertThat(last.getFailure()).isEqualTo(RunFailure.UNAVAILABLE);
        RunFailed event = lastEvent(Events.RUN_FAILED, RunFailed.class);
        assertThat(event.reason()).isEqualTo(RunFailure.UNAVAILABLE);
        assertThat(event.detail()).startsWith("No answer from the dungeon after 3 attempts");
    }

    @Test
    void theCircuitOpensAndStopsCallingADeadSimulator() {
        for (int i = 0; i < 4; i++) { // 4 runs x 3 quick attempts: past the 10-call minimum, all failing
            long party = lockParty();
            attemptOnly(party);
        }
        CircuitBreaker breaker = breakers.circuitBreaker(GuardedDungeonSim.NAME);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsSoFar = sim.calls.size();
        long party = lockParty();
        Run run = attemptOnly(party);
        assertThat(sim.calls).hasSize(callsSoFar);
        assertThat(run.getLastError()).isEqualTo("circuit open");
        assertThat(run.getStatus()).isEqualTo(Run.Status.PENDING);
    }

    @Test
    void aRedeliveredLockStartsOneRun() {
        long partyId = PARTIES.incrementAndGet();
        PartyLocked locked = new PartyLocked(7, partyId, List.of(new Member(1, "solo", Role.TANK)));
        EventEnvelope envelope = codec.envelope(Events.PARTY_LOCKED, "7", locked);
        service.start(envelope, locked);
        service.start(envelope, locked);
        assertThat(service.forParty(7, partyId)).hasSize(1);

        // the same party locking again later (after an aborted run) is a new event, and a new run
        service.start(new EventEnvelope(UUID.randomUUID(), envelope.type(), "7", clock.now, envelope.payload()), locked);
        assertThat(service.forParty(7, partyId)).hasSize(2);
    }

    @Test
    void aClaimedRunIsNotClaimedTwice() {
        lockParty();
        assertThat(service.claimDue()).hasSize(1);
        assertThat(service.claimDue()).isEmpty();
        clock.now = clock.now.plus(Duration.ofSeconds(30)); // the lease ran out: the worker must have died
        assertThat(service.claimDue()).hasSize(1);
    }

    @Test
    void backoffDoublesUpToTheCap() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(service.backoff(10)).isEqualTo(Duration.ofSeconds(15));
    }
}
