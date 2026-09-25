package com.csd.occ.operations.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion-topology")
public record IngestionTopologyProperties(URI baseUrl, Duration timeout) {
    public IngestionTopologyProperties {
        if (baseUrl == null || !("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("Ingestion topology URL must use HTTP or HTTPS");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Ingestion topology timeout must be positive");
        }
    }
}
