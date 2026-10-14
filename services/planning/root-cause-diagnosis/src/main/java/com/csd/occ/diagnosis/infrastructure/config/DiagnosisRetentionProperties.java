package com.csd.occ.diagnosis.infrastructure.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("occ.diagnosis.retention")
public record DiagnosisRetentionProperties(Duration duration, Duration interval, Duration initialDelay) {
    public DiagnosisRetentionProperties {
        if (duration == null || duration.isZero() || duration.isNegative() || interval == null || interval.isZero()
                || interval.isNegative() || initialDelay == null || initialDelay.isNegative())
            throw new IllegalArgumentException("Invalid diagnosis retention durations");
    }
}
