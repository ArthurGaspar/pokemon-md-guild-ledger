package dev.arthurgaspar.expeditions.platform.events;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** What goes on the wire: the payload plus what consumers need to deduplicate it. */
public record EventEnvelope(UUID id, String type, String aggregateId, Instant occurredAt, JsonNode payload) {
}
