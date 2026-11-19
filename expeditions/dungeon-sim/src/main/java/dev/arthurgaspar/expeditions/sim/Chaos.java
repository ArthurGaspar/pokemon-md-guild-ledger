package dev.arthurgaspar.expeditions.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How badly the simulator behaves. Starts from configuration ({@code sim.chaos.*}) and can be changed while it runs
 * through {@code PUT /api/chaos}, to watch the circuit breaker open and close.
 *
 * @param latencyMs fixed delay on every call
 * @param jitterMs  random extra delay, 0 to this
 * @param errorRate share of calls answered with 503
 * @param slowRate  share of calls that hang for {@code slowMs}, longer than any sensible client timeout
 * @param faintRate share of runs where the dungeon beats the party (a real outcome, not a fault)
 * @param outage    every call fails with 503
 */
@ConfigurationProperties("sim.chaos")
public record Chaos(@DefaultValue("300") long latencyMs, @DefaultValue("700") long jitterMs,
                    @DefaultValue("0.2") double errorRate, @DefaultValue("0.1") double slowRate,
                    @DefaultValue("5000") long slowMs, @DefaultValue("0.25") double faintRate,
                    @DefaultValue("false") boolean outage) {

    public Chaos {
        if (latencyMs < 0 || jitterMs < 0 || slowMs < 0) {
            throw new IllegalArgumentException("Delays can't be negative");
        }
        for (double rate : new double[] {errorRate, slowRate, faintRate}) {
            if (rate < 0 || rate > 1) {
                throw new IllegalArgumentException("Rates are between 0 and 1");
            }
        }
    }
}
