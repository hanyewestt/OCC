package com.csd.occ.ingestion.domain;

import java.time.Duration;

public record DemoReplayConfiguration(String scenario, long seed, Duration bunchingThreshold,
                                      java.util.Optional<java.util.UUID> captainUserId) {
    public DemoReplayConfiguration {
        java.util.Objects.requireNonNull(captainUserId);
    }
    public DemoReplayConfiguration(String scenario, long seed, Duration bunchingThreshold) {
        this(scenario, seed, bunchingThreshold, java.util.Optional.empty());
    }
}
