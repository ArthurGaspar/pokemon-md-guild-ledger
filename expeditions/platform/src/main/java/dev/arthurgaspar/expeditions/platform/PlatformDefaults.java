package dev.arthurgaspar.expeditions.platform;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Adds {@code expeditions-defaults.properties} (auth, OTLP export, Kafka tracing) as the lowest-precedence property
 * source, so every service shares them and any service, profile or environment variable can override them.
 */
public class PlatformDefaults implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            environment.getPropertySources().addLast(
                    new ResourcePropertySource("expeditions-defaults", new ClassPathResource("expeditions-defaults.properties")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
