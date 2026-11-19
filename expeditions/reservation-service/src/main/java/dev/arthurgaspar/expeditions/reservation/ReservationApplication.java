package dev.arthurgaspar.expeditions.reservation;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.platform.events.Topics;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ReservationSettings.class)
public class ReservationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationApplication.class, args);
    }

    @Bean
    AdmissionTokens admissionTokens(@Value("${expeditions.admission.secret}") String secret) {
        return new AdmissionTokens(secret);
    }

    @Bean
    NewTopic reservationEvents() {
        return TopicBuilder.name(Topics.RESERVATIONS).partitions(6).build();
    }
}
