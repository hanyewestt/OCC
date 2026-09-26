package com.csd.occ.operations.infrastructure.config;

import java.time.Duration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.map")
public record MapProperties(String tileUrl, String attributionText, URI attributionUrl, Duration refreshInterval,
        boolean rawObservationsDefault) {
    public MapProperties {
        if (tileUrl == null || !tileUrl.startsWith("https://") || attributionText == null
                || attributionUrl == null || refreshInterval == null || refreshInterval.toMillis() < 1000) {
            throw new IllegalArgumentException("Map tile settings and positive refresh interval are required");
        }
    }
}
