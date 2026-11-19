package dev.arthurgaspar.expeditions.platform.tracing;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Carries a trace across the gaps that instrumentation can't see: the outbox (written in a request, published later
 * by the relay) and the run worker (created by a consumer, attempted later by a poller). The W3C {@code traceparent}
 * is stored with the work, and the later step opens its span as a child of it.
 */
public class TraceContext {

    public static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current span as a {@code traceparent}, or null outside any trace. */
    public String current() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs {@code work} in a new span, a child of {@code traceparent} when there is one. */
    public <T> T inSpan(String name, String traceparent, Supplier<T> work) {
        Span span = traceparent == null
                ? tracer.nextSpan().name(name).start()
                : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get).name(name).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void inSpan(String name, String traceparent, Runnable work) {
        inSpan(name, traceparent, () -> {
            work.run();
            return null;
        });
    }
}
