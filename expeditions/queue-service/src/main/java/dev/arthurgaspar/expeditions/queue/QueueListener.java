package dev.arthurgaspar.expeditions.queue;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.CapacityChanged;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Events.ReservationHeld;
import dev.arthurgaspar.expeditions.platform.events.Topics;

/** Every handler is safe to repeat: registering sets the same fields, capacity is versioned, removals are removals. */
@Component
public class QueueListener {

    private final EventCodec codec;
    private final QueueStore store;

    public QueueListener(EventCodec codec, QueueStore store) {
        this.codec = codec;
        this.store = store;
    }

    @KafkaListener(topics = {Topics.EXPEDITIONS, Topics.RESERVATIONS}, groupId = "queue")
    void on(String message) {
        EventEnvelope envelope = codec.read(message);
        switch (envelope.type()) {
            case Events.EXPEDITION_PUBLISHED -> {
                ExpeditionPublished e = codec.payload(envelope, ExpeditionPublished.class);
                int partySize = e.composition().values().stream().mapToInt(Integer::intValue).sum();
                store.register(e.id(), e.opensAt(), e.parties() * partySize);
            }
            case Events.CAPACITY_CHANGED -> {
                CapacityChanged e = codec.payload(envelope, CapacityChanged.class);
                store.capacity(e.expeditionId(), e.openSlots(), e.version());
            }
            case Events.RESERVATION_HELD -> {
                ReservationHeld e = codec.payload(envelope, ReservationHeld.class);
                store.admissionUsed(e.expeditionId(), e.adventurerId());
            }
            default -> {
                // the rest of the saga doesn't concern the line
            }
        }
    }
}
