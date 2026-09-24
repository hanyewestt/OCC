package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("occ.ingestion.scheduling")
public record ArrivalSchedulingProperties(@DefaultValue("8") int maxConcurrentRequests,
                                           @DefaultValue("1") int maxStartsPerSecond) {
    public ArrivalSchedulingProperties {
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64
                || maxStartsPerSecond < 1 || maxStartsPerSecond > maxConcurrentRequests) {
            throw new IllegalArgumentException("Invalid arrival scheduling limits");
        }
    }
}
