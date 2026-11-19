package dev.arthurgaspar.expeditions.platform.events;

import java.time.Clock;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/** Envelopes to and from the JSON strings that go through the outbox and Kafka. */
public class EventCodec {

    private final JsonMapper json;
    private final Clock clock;

    public EventCodec(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    public EventEnvelope envelope(String type, String aggregateId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), type, aggregateId, clock.instant(), json.valueToTree(payload));
    }

    public String write(EventEnvelope envelope) {
        return json.writeValueAsString(envelope);
    }

    public EventEnvelope read(String message) {
        return json.readValue(message, EventEnvelope.class);
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        return json.treeToValue(envelope.payload(), type);
    }
}
