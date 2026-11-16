package dev.arthurgaspar.expeditions.issuance;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.arthurgaspar.expeditions.platform.events.Topics;

@SpringBootApplication
@EnableScheduling
public class IssuanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssuanceApplication.class, args);
    }

    @Bean
    NewTopic issuanceEvents() {
        return TopicBuilder.name(Topics.ISSUANCE).partitions(3).build();
    }
}
