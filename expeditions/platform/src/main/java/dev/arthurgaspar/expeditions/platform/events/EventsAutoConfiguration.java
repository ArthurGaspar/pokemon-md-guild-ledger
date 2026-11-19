package dev.arthurgaspar.expeditions.platform.events;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(JsonMapper json, Clock clock) {
        return new EventCodec(json, clock);
    }
}
