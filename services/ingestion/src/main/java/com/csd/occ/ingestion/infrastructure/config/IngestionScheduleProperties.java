package com.csd.occ.ingestion.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.schedule")
public record IngestionScheduleProperties(Duration busArrivalPollInterval,
        Duration busArrivalInitialDelay, Duration retentionInterval, Duration retentionInitialDelay) {
    public IngestionScheduleProperties {
        bounded(busArrivalPollInterval, Duration.ofSeconds(1), Duration.ofHours(1), "bus-arrival-poll-interval");
        bounded(busArrivalInitialDelay, Duration.ZERO, Duration.ofHours(1), "bus-arrival-initial-delay");
        bounded(retentionInterval, Duration.ofMinutes(1), Duration.ofHours(24), "retention-interval");
        bounded(retentionInitialDelay, Duration.ZERO, Duration.ofHours(24), "retention-initial-delay");
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " must be from " + minimum + " through " + maximum);
    }
}
