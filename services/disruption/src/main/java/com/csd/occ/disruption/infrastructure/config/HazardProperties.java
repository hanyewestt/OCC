package com.csd.occ.disruption.infrastructure.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.disruption.hazards")
public record HazardProperties(boolean enabled, Duration pollInterval, Duration staleAfter,
                               int absenceLimit, Duration absencePeriod, double associationRadiusKm) {
    public HazardProperties {
        Objects.requireNonNull(pollInterval); Objects.requireNonNull(staleAfter); Objects.requireNonNull(absencePeriod);
        if (!Double.isFinite(associationRadiusKm) || associationRadiusKm <= 0)
            throw new IllegalArgumentException("Hazard association radius must be positive");
        if (pollInterval.isZero() || pollInterval.isNegative() || staleAfter.isNegative()
                || staleAfter.isZero() || absenceLimit < 1 || absencePeriod.isNegative())
            throw new IllegalArgumentException("Invalid hazard synchronization settings");
    }
}
