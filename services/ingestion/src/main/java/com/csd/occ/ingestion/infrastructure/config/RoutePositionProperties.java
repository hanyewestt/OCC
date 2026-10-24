package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.route-positions")
public record RoutePositionProperties(double clusterToleranceMeters, int maxPollAgeCycles,
                                      double speedCapMetersPerSecond) {
    public RoutePositionProperties {
        if (!Double.isFinite(clusterToleranceMeters) || clusterToleranceMeters <= 0)
            throw new IllegalArgumentException("route-position cluster-tolerance-meters must be positive");
        if (maxPollAgeCycles < 1 || maxPollAgeCycles > 5)
            throw new IllegalArgumentException("route-position max-poll-age-cycles must be from 1 through 5");
        if (!Double.isFinite(speedCapMetersPerSecond) || speedCapMetersPerSecond <= 0
                || speedCapMetersPerSecond > 40)
            throw new IllegalArgumentException("route-position speed-cap-meters-per-second must be from 0 through 40");
    }
}
