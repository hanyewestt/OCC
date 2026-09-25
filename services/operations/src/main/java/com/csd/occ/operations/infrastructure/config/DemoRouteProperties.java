package com.csd.occ.operations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.demo-route")
public record DemoRouteProperties(double maxStopDistanceMeters) {
    public DemoRouteProperties {
        if (!Double.isFinite(maxStopDistanceMeters) || maxStopDistanceMeters <= 0) {
            throw new IllegalArgumentException("Maximum demo stop distance must be positive and finite");
        }
    }
}
