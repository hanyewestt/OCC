package com.csd.occ.gateway.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "occ.frontend")
public record FrontendProperties(URI projectUrl, String publishableKey, Duration controllerRefreshInterval,
                                 Duration ingestionPollInterval, Duration routeIncidentGapTolerance) {
    public FrontendProperties {
        if (publishableKey != null && !publishableKey.isBlank()
                && !publishableKey.startsWith("sb_publishable_")) {
            throw new IllegalArgumentException("Frontend configuration accepts only a Supabase publishable key");
        }
        requireAtLeastOneSecond(controllerRefreshInterval, "controller refresh interval");
        requireAtLeastOneSecond(ingestionPollInterval, "ingestion poll interval");
        requireAtLeastOneSecond(routeIncidentGapTolerance, "route incident gap tolerance");
    }

    private static void requireAtLeastOneSecond(Duration value, String name) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least one second");
        }
    }
}
