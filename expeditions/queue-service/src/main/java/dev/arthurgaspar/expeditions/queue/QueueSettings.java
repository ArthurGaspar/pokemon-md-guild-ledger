package dev.arthurgaspar.expeditions.queue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param admissionTtl how long an admitted adventurer has to reserve
 * @param batch        the most adventurers admitted per expedition per run, so a large release of slots reaches
 *                     reservation as a steady flow instead of one burst
 */
@ConfigurationProperties("queue")
public record QueueSettings(@DefaultValue("PT2M") Duration admissionTtl, @DefaultValue("200") int batch) {
}
