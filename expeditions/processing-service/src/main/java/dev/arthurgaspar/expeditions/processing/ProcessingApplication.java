package dev.arthurgaspar.expeditions.processing;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import dev.arthurgaspar.expeditions.platform.events.Topics;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ProcessingSettings.class)
public class ProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingApplication.class, args);
    }

    @Bean
    DungeonSim dungeonSim(RestClient.Builder builder,
                          @Value("${processing.sim.url}") String url,
                          @Value("${processing.sim.connect-timeout:1s}") Duration connectTimeout,
                          @Value("${processing.sim.read-timeout:2s}") Duration readTimeout) {
        return new HttpDungeonSim(builder, url, connectTimeout, readTimeout);
    }

    @Bean
    NewTopic runEvents() {
        return TopicBuilder.name(Topics.RUNS).partitions(6).build();
    }
}
