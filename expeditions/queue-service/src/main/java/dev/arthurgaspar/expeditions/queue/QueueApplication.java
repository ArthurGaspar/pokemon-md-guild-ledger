package dev.arthurgaspar.expeditions.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(QueueSettings.class)
public class QueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueApplication.class, args);
    }

    @Bean
    AdmissionTokens admissionTokens(@Value("${expeditions.admission.secret}") String secret) {
        return new AdmissionTokens(secret);
    }
}
