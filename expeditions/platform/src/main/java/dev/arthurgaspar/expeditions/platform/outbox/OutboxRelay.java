package dev.arthurgaspar.expeditions.platform.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import dev.arthurgaspar.expeditions.platform.tracing.TraceContext;

/**
 * Publishes committed outbox rows to Kafka, oldest first. Rows are locked with {@code SKIP LOCKED}, so several
 * instances can relay without sending the same row twice at the same moment; a crash between the send and the
 * update can still send a row twice, which is why consumers deduplicate.
 * <p>
 * Each send runs in a span that continues the trace the event was written in, so Kafka's producer instrumentation
 * puts that trace in the record headers and the consumer carries on with it.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String topic, String key, String type, String payload, String traceParent) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;
    private final Duration retention;
    private final TraceContext traces;

    /** @param traces null when tracing is off */
    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka, Clock clock,
                       int batchSize, Duration retention, TraceContext traces) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retention = retention;
        this.traces = traces;
    }

    @Scheduled(fixedDelayString = "${expeditions.outbox.relay-interval:200ms}")
    public void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> relayBatch());
            } while (sent != null && sent == batchSize);
        } catch (RuntimeException e) {
            // the rows stay unpublished and the next run retries them
            log.warn("Outbox relay failed: {}", e.getMessage());
        }
    }

    private int relayBatch() {
        List<Row> rows = jdbc.query("""
                select id, topic, message_key, event_type, payload, trace_parent from outbox
                where published_at is null order by id limit ? for update skip locked""",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6)), batchSize);
        if (rows.isEmpty()) {
            return 0;
        }
        // the idempotent producer keeps per-partition order even with these sends in flight together
        CompletableFuture<?>[] sends = rows.stream()
                .map(this::send)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(sends).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge the batch", e);
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("update outbox set published_at = ? where id = ?",
                rows.stream().map(r -> new Object[] {now, r.id()}).toList());
        return rows.size();
    }

    private CompletableFuture<?> send(Row r) {
        if (traces == null || r.traceParent() == null) {
            return kafka.send(r.topic(), r.key(), r.payload());
        }
        return traces.inSpan("outbox " + r.type(), r.traceParent(), () -> kafka.send(r.topic(), r.key(), r.payload()));
    }

    @Scheduled(fixedDelayString = "${expeditions.outbox.cleanup-interval:10m}")
    public void cleanUp() {
        int removed = jdbc.update("delete from outbox where published_at < ?", Timestamp.from(clock.instant().minus(retention)));
        if (removed > 0) {
            log.info("Removed {} published outbox rows", removed);
        }
    }
}
