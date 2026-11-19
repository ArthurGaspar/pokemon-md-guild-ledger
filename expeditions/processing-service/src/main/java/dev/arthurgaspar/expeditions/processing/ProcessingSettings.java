package dev.arthurgaspar.expeditions.processing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param runDeadline how long a run may keep failing before it is given up as {@code UNAVAILABLE}
 * @param lease       how long a worker owns a run per attempt; a crashed worker's runs come back after it
 * @param retryBase   wait after the first failed attempt (after Resilience4j's own quick retries), doubling
 * @param retryMax    the longest wait between attempts
 * @param batch       runs claimed per poll
 */
@ConfigurationProperties("processing")
public record ProcessingSettings(@DefaultValue("PT2M") Duration runDeadline, @DefaultValue("PT30S") Duration lease,
                                 @DefaultValue("PT1S") Duration retryBase, @DefaultValue("PT15S") Duration retryMax,
                                 @DefaultValue("20") int batch) {
}
