package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("occ.datamall")
public record DataMallProperties(URI baseUrl, String accountKey, Duration connectTimeout, Duration responseTimeout) {
    public DataMallProperties {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getScheme() == null
                || !(baseUrl.getScheme().equals("http") || baseUrl.getScheme().equals("https")))
            throw new IllegalArgumentException("base-url must be an absolute HTTP(S) URL");
        if (accountKey == null || accountKey.isBlank() || accountKey.equalsIgnoreCase("mock_key")
                || accountKey.equalsIgnoreCase("change-me") || accountKey.equalsIgnoreCase("replace-with-real-key"))
            throw new IllegalArgumentException("DATAMALL_API_KEY must be a configured credential");
        bounded(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30), "connect-timeout");
        bounded(responseTimeout, Duration.ofMillis(100), Duration.ofMinutes(2), "response-timeout");
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " must be from " + minimum + " through " + maximum);
    }
}
