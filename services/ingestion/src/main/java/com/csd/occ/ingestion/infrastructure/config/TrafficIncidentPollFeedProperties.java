package com.csd.occ.ingestion.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("occ.ingestion.traffic-feed")
public record TrafficIncidentPollFeedProperties(@Min(1) @Max(500) int defaultPageSize,
                                                @Min(1) @Max(1000) int maxPageSize) {
    public TrafficIncidentPollFeedProperties {
        if (defaultPageSize > maxPageSize)
            throw new IllegalArgumentException("default-page-size must not exceed max-page-size");
    }
}
