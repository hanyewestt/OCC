package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("occ.ingestion.arrival")
public record ArrivalPollingProperties(URI baseUrl, String accountKey, Duration timeout, Duration lease,
                                       int maxResponseBytes, int staleAfterSeconds) {
    public ArrivalPollingProperties {
        if (baseUrl == null || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || accountKey == null || accountKey.isBlank()
                || timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || lease == null || lease.compareTo(timeout.plusSeconds(5)) <= 0
                || maxResponseBytes < 1 || maxResponseBytes > 10_000_000 || staleAfterSeconds < 1) {
            throw new IllegalArgumentException("Invalid arrival polling configuration");
        }
    }

    // Records otherwise print every field, including the provider credential.
    @Override public String toString() { return "ArrivalPollingProperties[credentials redacted]"; }
}
