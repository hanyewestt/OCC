package com.csd.occ.ingestion.infrastructure.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.hazards")
public record HazardFeedProperties(Feed flood, Feed rail, Feed roadworks, int pageSize, int maxPages,
                                   ZoneId sourceTimezone) {
    public HazardFeedProperties {
        Objects.requireNonNull(flood); Objects.requireNonNull(rail); Objects.requireNonNull(roadworks);
        Objects.requireNonNull(sourceTimezone);
        if (pageSize < 1 || pageSize > 500 || maxPages < 1 || maxPages > 100)
            throw new IllegalArgumentException("Hazard pagination must be 1–500 records and 1–100 pages");
    }
    public record Feed(boolean enabled, Duration interval, Duration initialDelay, Duration staleAfter) {
        public Feed {
            Objects.requireNonNull(interval); Objects.requireNonNull(initialDelay); Objects.requireNonNull(staleAfter);
            if (interval.compareTo(Duration.ofSeconds(1)) < 0 || initialDelay.isNegative()
                    || staleAfter.compareTo(interval) < 0)
                throw new IllegalArgumentException("Invalid hazard feed schedule or freshness");
        }
    }
}
