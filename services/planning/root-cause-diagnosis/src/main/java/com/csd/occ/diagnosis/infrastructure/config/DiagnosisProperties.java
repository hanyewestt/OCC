package com.csd.occ.diagnosis.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.diagnosis")
public record DiagnosisProperties(
        URI bunchingInternalUrl,
        URI ingestionInternalUrl,
        URI recommendationInternalUrl,
        boolean workerEnabled,
        Duration workerInterval,
        int workerBatchSize,
        Duration leaseDuration,
        int maxAttempts,
        int deliveryMaxAttempts,
        Duration retryInitialDelay,
        Duration retryMaxDelay,
        int schemaRepairAttempts,
        Duration evidenceLookback,
        double evidenceRadiusKm,
        double confidenceThreshold,
        Duration requestTimeout,
        long peerResponseMaxBytes,
        long maxPromptInputBytes) {

    public DiagnosisProperties {
        requireHttp(bunchingInternalUrl, "bunching-internal-url");
        requireHttp(ingestionInternalUrl, "ingestion-internal-url");
        requireHttp(recommendationInternalUrl, "recommendation-internal-url");
        positive(workerInterval, "worker-interval");
        positive(leaseDuration, "lease-duration");
        positive(retryInitialDelay, "retry-initial-delay");
        positive(retryMaxDelay, "retry-max-delay");
        positive(evidenceLookback, "evidence-lookback");
        positive(requestTimeout, "request-timeout");
        if (peerResponseMaxBytes < 16_384 || peerResponseMaxBytes > 1_048_576)
            throw new IllegalArgumentException("peer-response-max-bytes must be 16384..1048576");
        if (maxPromptInputBytes < 8_192 || maxPromptInputBytes > 262_144)
            throw new IllegalArgumentException("max-prompt-input-bytes must be 8192..262144");
        if (workerBatchSize < 1 || workerBatchSize > 100) {
            throw new IllegalArgumentException("worker-batch-size must be in 1..100");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("max-attempts must be in 1..10");
        }
        if (deliveryMaxAttempts < 0 || deliveryMaxAttempts > 1000) {
            throw new IllegalArgumentException("delivery-max-attempts must be in 0..1000; zero retries indefinitely");
        }
        if (schemaRepairAttempts != 1) {
            throw new IllegalArgumentException("schema-repair-attempts must be exactly one for the MVP");
        }
        if (!Double.isFinite(evidenceRadiusKm) || evidenceRadiusKm <= 0) {
            throw new IllegalArgumentException("evidence-radius-km must be positive");
        }
        if (!Double.isFinite(confidenceThreshold) || confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidence-threshold must be from zero to one");
        }
        if (retryMaxDelay.compareTo(retryInitialDelay) < 0) {
            throw new IllegalArgumentException("retry-max-delay must not be less than retry-initial-delay");
        }
    }

    private static void requireHttp(URI uri, String name) {
        if (uri == null || uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
