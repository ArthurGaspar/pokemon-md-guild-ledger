package dev.arthurgaspar.expeditions.reservation;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Topics;

@Component
public class ExpeditionListener {

    private final EventCodec codec;
    private final ReservationService service;

    public ExpeditionListener(EventCodec codec, ReservationService service) {
        this.codec = codec;
        this.service = service;
    }

    @KafkaListener(topics = Topics.EXPEDITIONS, groupId = "reservation")
    void on(String message) {
        EventEnvelope envelope = codec.read(message);
        if (Events.EXPEDITION_PUBLISHED.equals(envelope.type())) {
            service.register(codec.payload(envelope, ExpeditionPublished.class));
        }
    }
}
