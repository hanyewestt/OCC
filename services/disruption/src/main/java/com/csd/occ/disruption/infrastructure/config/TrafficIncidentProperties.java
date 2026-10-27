package com.csd.occ.disruption.infrastructure.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.disruption.traffic")
public record TrafficIncidentProperties(boolean pollEnabled, Duration pollInterval, int pageSize,
        Duration staleAfter, int clearAfterSuccessfulAbsences, Duration minimumAbsencePeriod) {
    public TrafficIncidentProperties(boolean pollEnabled, Duration pollInterval, int pageSize,
            Duration staleAfter, int clearAfterSuccessfulAbsences) {
        this(pollEnabled, pollInterval, pageSize, staleAfter, clearAfterSuccessfulAbsences, Duration.ZERO);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public TrafficIncidentProperties {
        Objects.requireNonNull(pollInterval, "poll-interval");
        Objects.requireNonNull(staleAfter, "stale-after");
        Objects.requireNonNull(minimumAbsencePeriod, "minimum-absence-period");
        if (minimumAbsencePeriod.isNegative()) throw new IllegalArgumentException("minimum absence period cannot be negative");
        if (pollInterval.isZero() || pollInterval.isNegative()) throw new IllegalArgumentException("poll-interval must be positive");
        if (pageSize < 1 || pageSize > 250) throw new IllegalArgumentException("page-size must be 1..250");
        if (staleAfter.isZero() || staleAfter.isNegative()) throw new IllegalArgumentException("stale-after must be positive");
        if (clearAfterSuccessfulAbsences < 1 || clearAfterSuccessfulAbsences > 20)
            throw new IllegalArgumentException("clear-after-successful-absences must be 1..20");
    }
}
