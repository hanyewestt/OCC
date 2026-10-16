package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("occ.datamall")
public record DataMallProperties(URI baseUrl, String accountKey, Duration connectTimeout, Duration responseTimeout,
        int trafficIncidentPageSize, int trafficIncidentMaxPages, int maxResponseBytes) {
    public DataMallProperties {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getScheme() == null
                || !(baseUrl.getScheme().equals("http") || baseUrl.getScheme().equals("https")))
            throw new IllegalArgumentException("base-url must be an absolute HTTP(S) URL");
        if (accountKey == null || accountKey.isBlank() || accountKey.equalsIgnoreCase("mock_key")
                || accountKey.equalsIgnoreCase("change-me") || accountKey.equalsIgnoreCase("replace-with-real-key"))
            throw new IllegalArgumentException("DATAMALL_API_KEY must be a configured credential");
        bounded(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30), "connect-timeout");
        bounded(responseTimeout, Duration.ofMillis(100), Duration.ofMinutes(2), "response-timeout");
        if (trafficIncidentPageSize < 1 || trafficIncidentPageSize > 10_000)
            throw new IllegalArgumentException("traffic-incident-page-size must be between 1 and 10000");
        if (trafficIncidentMaxPages < 1 || trafficIncidentMaxPages > 100)
            throw new IllegalArgumentException("traffic-incident-max-pages must be between 1 and 100");
        if ((long) trafficIncidentPageSize * trafficIncidentMaxPages > 50_000)
            throw new IllegalArgumentException("traffic incident polling may read at most 50000 records per poll");
        if (maxResponseBytes < 1_024 || maxResponseBytes > 20_000_000)
            throw new IllegalArgumentException("max-response-bytes must be between 1024 and 20000000");
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " must be from " + minimum + " through " + maximum);
    }
}
