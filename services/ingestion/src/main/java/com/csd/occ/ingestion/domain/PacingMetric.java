package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pacing for one bus at the stop whose predictions produced it. {@code ahead} or {@code behind} is
 * empty when that neighbour is not in the same prediction set.
 */
public record PacingMetric(String vehicleReference, String classification, String stopCode, String serviceId,
                           int directionId, Duration targetHeadway, Optional<PacingGap> ahead,
                           Optional<PacingGap> behind, Instant observedAt) {
    public PacingMetric {
        Objects.requireNonNull(vehicleReference, "vehicleReference");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(stopCode, "stopCode");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(targetHeadway, "targetHeadway");
        Objects.requireNonNull(ahead, "ahead");
        Objects.requireNonNull(behind, "behind");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
