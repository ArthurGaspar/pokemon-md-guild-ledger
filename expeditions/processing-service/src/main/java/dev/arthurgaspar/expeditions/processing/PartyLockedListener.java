package dev.arthurgaspar.expeditions.processing;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Topics;

@Component
public class PartyLockedListener {

    private final EventCodec codec;
    private final RunService service;

    public PartyLockedListener(EventCodec codec, RunService service) {
        this.codec = codec;
        this.service = service;
    }

    @KafkaListener(topics = Topics.RESERVATIONS, groupId = "processing")
    void on(String message) {
        EventEnvelope envelope = codec.read(message);
        if (Events.PARTY_LOCKED.equals(envelope.type())) {
            service.start(envelope, codec.payload(envelope, PartyLocked.class));
        }
    }
}
