package com.csd.occ.ingestion.infrastructure.config;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("occ.ingestion.demo")
public record DemoProperties(boolean enabled, String scenario, long seed, Duration stepInterval,
                             Duration bunchingThreshold, URI bunchingInternalUrl,
                             Duration bunchingControlTimeout) {
    public DemoProperties {
        Objects.requireNonNull(scenario); Objects.requireNonNull(stepInterval); Objects.requireNonNull(bunchingThreshold);
        Objects.requireNonNull(bunchingInternalUrl);
        Objects.requireNonNull(bunchingControlTimeout);
        if (stepInterval.isZero() || stepInterval.isNegative()) throw new IllegalArgumentException("demo step interval must be positive");
        if (bunchingThreshold.isNegative() || bunchingThreshold.compareTo(Duration.ofNanos(2)) < 0)
            throw new IllegalArgumentException("demo bunching threshold must be at least 2ns");
        if (!scenario.matches("[a-z0-9-]+-v[0-9]+")) throw new IllegalArgumentException("demo scenario must include a version suffix");
        if (seed < 0) throw new IllegalArgumentException("demo seed must be non-negative");
    }
}
