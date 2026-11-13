package dev.arthurgaspar.expeditions.catalog;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.arthurgaspar.expeditions.platform.events.Topics;

@SpringBootApplication
@EnableScheduling
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    /** Compacted: one event per expedition, kept, so a consumer that starts later still sees every expedition. */
    @Bean
    NewTopic expeditionEvents() {
        return TopicBuilder.name(Topics.EXPEDITIONS).partitions(3).compact().build();
    }
}
