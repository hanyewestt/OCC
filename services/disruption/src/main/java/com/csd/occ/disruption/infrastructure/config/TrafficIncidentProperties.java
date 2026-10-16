package com.csd.occ.disruption.infrastructure.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.disruption.traffic")
public record TrafficIncidentProperties(boolean pollEnabled, Duration pollInterval, int pageSize,
        Duration staleAfter, int clearAfterSuccessfulAbsences) {
    public TrafficIncidentProperties {
        Objects.requireNonNull(pollInterval, "poll-interval");
        Objects.requireNonNull(staleAfter, "stale-after");
        if (pollInterval.isZero() || pollInterval.isNegative()) throw new IllegalArgumentException("poll-interval must be positive");
        if (pageSize < 1 || pageSize > 250) throw new IllegalArgumentException("page-size must be 1..250");
        if (staleAfter.isZero() || staleAfter.isNegative()) throw new IllegalArgumentException("stale-after must be positive");
        if (clearAfterSuccessfulAbsences < 1 || clearAfterSuccessfulAbsences > 20)
            throw new IllegalArgumentException("clear-after-successful-absences must be 1..20");
    }
}
