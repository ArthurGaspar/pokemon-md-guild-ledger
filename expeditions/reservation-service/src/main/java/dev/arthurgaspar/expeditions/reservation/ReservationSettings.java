package dev.arthurgaspar.expeditions.reservation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param holdTtl    how long a slot in a forming party stays held
 * @param readyCheck how long an assembled party has to confirm
 */
@ConfigurationProperties("reservation")
public record ReservationSettings(@DefaultValue("PT10M") Duration holdTtl, @DefaultValue("PT60S") Duration readyCheck) {
}
