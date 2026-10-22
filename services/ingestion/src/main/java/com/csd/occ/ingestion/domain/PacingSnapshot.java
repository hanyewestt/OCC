package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** All pacing metrics from one calculation cycle. */
public record PacingSnapshot(Instant calculatedAt, List<PacingMetric> metrics) {
    public PacingSnapshot {
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        metrics = List.copyOf(metrics);
    }

    public PacingSnapshot filtered(Predicate<PacingMetric> include) {
        return new PacingSnapshot(calculatedAt, metrics.stream().filter(include).toList());
    }
}
