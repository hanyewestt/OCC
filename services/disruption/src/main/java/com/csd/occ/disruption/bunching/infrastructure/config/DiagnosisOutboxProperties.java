package com.csd.occ.disruption.bunching.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.disruption.planning")
public record DiagnosisOutboxProperties(URI internalUrl, boolean workerEnabled, Duration deliveryInterval,
        Duration requestTimeout, Duration leaseDuration, int batchSize, int maxAttempts,
        Duration retryInitialDelay, Duration retryMaxDelay) {
    public DiagnosisOutboxProperties {
        if (internalUrl == null || internalUrl.getHost() == null || internalUrl.getScheme() == null
                || !(internalUrl.getScheme().equals("http") || internalUrl.getScheme().equals("https")))
            throw new IllegalArgumentException("internal-url must be an absolute HTTP(S) URL");
        positive(deliveryInterval, "delivery-interval"); positive(requestTimeout, "request-timeout");
        positive(leaseDuration, "lease-duration");
        positive(retryInitialDelay, "retry-initial-delay"); positive(retryMaxDelay, "retry-max-delay");
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("batch-size must be 1..100");
        if (maxAttempts < 0 || maxAttempts > 1000) throw new IllegalArgumentException("max-attempts must be 0..1000; zero retries indefinitely");
        if (retryMaxDelay.compareTo(retryInitialDelay) < 0) throw new IllegalArgumentException("retry-max-delay must be at least retry-initial-delay");
    }
    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }
}
