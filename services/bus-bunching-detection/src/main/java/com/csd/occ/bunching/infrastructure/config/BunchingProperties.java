package com.csd.occ.bunching.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("occ.bunching")
public record BunchingProperties(Duration threshold, Duration staleAfter, Duration pollInterval, int batchSize,
                                 java.net.URI ingestionBaseUrl, Duration retryDelay) {
    public BunchingProperties {
        positive(threshold, "threshold");
        positive(staleAfter, "stale-after");
        positive(pollInterval, "poll-interval");
        if (pollInterval.toMillis() < 1) throw new IllegalArgumentException("poll-interval must be at least 1ms");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batch-size must be 1..1000");
        Objects.requireNonNull(ingestionBaseUrl, "ingestion-base-url");
        positive(retryDelay, "retry-delay");
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }
}
