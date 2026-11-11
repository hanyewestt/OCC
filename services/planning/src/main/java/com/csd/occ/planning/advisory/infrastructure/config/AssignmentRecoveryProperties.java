package com.csd.occ.planning.advisory.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.planning.assignment-recovery")
public record AssignmentRecoveryProperties(Duration interval, int batchSize) {
    public AssignmentRecoveryProperties {
        if (interval == null || interval.isZero() || interval.isNegative() || batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("Invalid assignment recovery settings");
    }
}
