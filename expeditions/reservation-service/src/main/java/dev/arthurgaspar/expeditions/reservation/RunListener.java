package dev.arthurgaspar.expeditions.reservation;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.RunCompleted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailed;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;
import dev.arthurgaspar.expeditions.platform.events.Topics;

/** The end of the saga for reservation. Each handler acts only on a LOCKED party, so redeliveries do nothing. */
@Component
public class RunListener {

    private final EventCodec codec;
    private final ReservationService service;

    public RunListener(EventCodec codec, ReservationService service) {
        this.codec = codec;
        this.service = service;
    }

    @KafkaListener(topics = Topics.RUNS, groupId = "reservation")
    void on(String message) {
        EventEnvelope envelope = codec.read(message);
        switch (envelope.type()) {
            case Events.RUN_COMPLETED -> {
                RunCompleted e = codec.payload(envelope, RunCompleted.class);
                service.runFinished(e.expeditionId(), e.partyId(), true);
            }
            case Events.RUN_FAILED -> {
                RunFailed e = codec.payload(envelope, RunFailed.class);
                if (e.reason() == RunFailure.FAINTED) {
                    service.runFinished(e.expeditionId(), e.partyId(), false);
                } else {
                    service.runAborted(e.expeditionId(), e.partyId(), e.detail());
                }
            }
            default -> {
            }
        }
    }
}
