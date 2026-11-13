package dev.arthurgaspar.expeditions.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;

/**
 * Records an event in the same transaction as the state change it describes. {@link OutboxRelay} publishes it
 * afterwards, so an event goes out if and only if the change committed.
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;

    public Outbox(JdbcTemplate jdbc, EventCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    /** @param key the Kafka record key: the expedition id, so its events keep their order */
    public EventEnvelope append(String topic, String key, String type, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.append must run inside the transaction that changes the state");
        }
        EventEnvelope envelope = codec.envelope(type, key, payload);
        jdbc.update("insert into outbox (topic, message_key, event_type, payload, created_at) values (?, ?, ?, ?, ?)",
                topic, key, type, codec.write(envelope), java.sql.Timestamp.from(envelope.occurredAt()));
        return envelope;
    }
}
