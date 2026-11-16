package dev.arthurgaspar.expeditions.issuance;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.Topics;

@Component
public class IssuanceListener {

    private final EventCodec codec;
    private final IssuanceService service;

    public IssuanceListener(EventCodec codec, IssuanceService service) {
        this.codec = codec;
        this.service = service;
    }

    @KafkaListener(topics = {Topics.RESERVATIONS, Topics.RUNS}, groupId = "issuance")
    void on(String message) {
        service.handle(codec.read(message));
    }
}
