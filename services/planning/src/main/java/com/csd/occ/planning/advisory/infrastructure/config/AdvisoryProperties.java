package com.csd.occ.planning.advisory.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.planning.advisory")
public record AdvisoryProperties(int maxSpeedReductionKmh, int maxDurationSeconds, int maxSubsequentStops,
                                 Duration acknowledgementWindow, Duration retention) {
    public AdvisoryProperties {
        if (maxSpeedReductionKmh < 1 || maxDurationSeconds < 60 || maxSubsequentStops < 0
                || acknowledgementWindow == null || acknowledgementWindow.isNegative() || acknowledgementWindow.isZero()
                || retention == null || retention.isNegative() || retention.isZero())
            throw new IllegalArgumentException("Invalid advisory limits or retention");
    }
}
