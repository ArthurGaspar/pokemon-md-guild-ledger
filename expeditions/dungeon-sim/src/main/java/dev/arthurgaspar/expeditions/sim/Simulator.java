package dev.arthurgaspar.expeditions.sim;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Faults first (they model the network and an overloaded partner), then the outcome. The outcome is a pure function
 * of the idempotency key, and it is remembered, so a retried run gets the same answer.
 */
@Component
public class Simulator {

    public enum Outcome { CLEARED, FAINTED }

    public record Result(String runId, Outcome outcome, int floors, int floorsCleared, int pokeFound) {}

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    static final int FLOORS = 20;

    private final AtomicReference<Chaos> chaos;
    private final Map<String, Result> results = new ConcurrentHashMap<>();

    public Simulator(Chaos initial) {
        this.chaos = new AtomicReference<>(initial);
    }

    public Chaos chaos() {
        return chaos.get();
    }

    public void chaos(Chaos next) {
        chaos.set(next);
    }

    public Result run(String runId, List<String> members) throws InterruptedException {
        Chaos c = chaos.get();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Thread.sleep(c.latencyMs() + (c.jitterMs() == 0 ? 0 : random.nextLong(c.jitterMs() + 1)));
        if (c.outage()) {
            throw new Unavailable("The dungeon is closed for maintenance");
        }
        if (random.nextDouble() < c.slowRate()) {
            Thread.sleep(c.slowMs());
        }
        if (random.nextDouble() < c.errorRate()) {
            throw new Unavailable("The dungeon is overloaded, try again");
        }
        return results.computeIfAbsent(runId, id -> outcome(id, members, c.faintRate()));
    }

    private static Result outcome(String runId, List<String> members, double faintRate) {
        Random dice = new Random(runId.hashCode());
        if (dice.nextDouble() < faintRate) {
            return new Result(runId, Outcome.FAINTED, FLOORS, 1 + dice.nextInt(FLOORS - 1), dice.nextInt(200));
        }
        return new Result(runId, Outcome.CLEARED, FLOORS, FLOORS, 400 * members.size() + dice.nextInt(1600));
    }
}
