package com.csd.occ.bunching.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.bunching.retention")
public record BunchingRetentionProperties(Duration duration, Duration interval, Duration initialDelay) {
    public BunchingRetentionProperties {
        if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("retention.duration must be positive");
        if (interval == null || interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("retention.interval must be positive");
        if (initialDelay == null || initialDelay.isNegative()) throw new IllegalArgumentException("retention.initial-delay must not be negative");
    }
}
