package dev.arthurgaspar.expeditions.platform.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;

/**
 * Deduplicates consumed events for handlers whose effect isn't naturally idempotent. Call it first, in the same
 * transaction as the handler: if the handler rolls back, so does the record, and the redelivery is processed.
 */
public class Inbox {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Inbox(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return false when this event was already processed */
    public boolean firstDelivery(EventEnvelope envelope) {
        return firstDelivery(envelope.id());
    }

    public boolean firstDelivery(UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inbox.firstDelivery must run inside the handler's transaction");
        }
        // not catch-on-duplicate: a failed insert would abort the whole PostgreSQL transaction
        return jdbc.update("insert into inbox (event_id, processed_at) values (?, ?) on conflict do nothing",
                eventId.toString(), Timestamp.from(clock.instant())) == 1;
    }
}
