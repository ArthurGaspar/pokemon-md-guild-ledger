package dev.arthurgaspar.expeditions.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.tracing.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** An event written inside a span keeps that span's trace, which the relay then continues. */
@SpringBootTest(properties = "management.tracing.enabled=true")
@ActiveProfiles("test")
class OutboxTracingTest {

    @Autowired
    ExpeditionService service;

    @Autowired
    Tracer tracer;

    @Autowired
    TraceContext traces;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theOutboxRowCarriesTheTraceItWasWrittenIn() {
        Span span = tracer.nextSpan().name("publish").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            service.publish("wigglytuff", new ExpeditionService.Draft("traced-1", "Traced", "sky-peak", "STAR",
                    Instant.parse("2026-12-01T00:00:00Z"), 1, Map.of(Role.TANK, 1, Role.DAMAGE, 1)));
        } finally {
            span.end();
        }
        String traceParent = jdbc.queryForObject("select trace_parent from outbox order by id desc limit 1", String.class);
        assertThat(traceParent).isNotNull().contains(span.context().traceId());

        // and the relay's span continues it
        String relayTrace = traces.inSpan("outbox test", traceParent, () -> tracer.currentSpan().context().traceId());
        assertThat(relayTrace).isEqualTo(span.context().traceId());
    }
}
