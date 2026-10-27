package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.station-reference")
public record StationReferenceProperties(String location, int maxBytes) {
    public StationReferenceProperties {
        location = location == null ? "" : location.trim();
        if (maxBytes < 1024 || maxBytes > 20_000_000) throw new IllegalArgumentException("Invalid station import byte limit");
    }
}
