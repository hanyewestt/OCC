package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PacingConfigurationPort;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.pacing")
public record PacingProperties(
        Duration interval,
        Duration targetHeadway,
        double tooCloseRatio,
        double tooFarRatio,
        Duration staleAfter,
        Duration streamTimeout,
        int maxSubscribers) implements PacingConfigurationPort {
    public PacingProperties {
        positive(interval, "interval");
        positive(targetHeadway, "target-headway");
        positive(staleAfter, "stale-after");
        positive(streamTimeout, "stream-timeout");
        if (!(tooCloseRatio > 0 && tooCloseRatio < 1))
            throw new IllegalArgumentException("Pacing too-close-ratio must be between 0 and 1");
        if (!(tooFarRatio > 1))
            throw new IllegalArgumentException("Pacing too-far-ratio must be greater than 1");
        if (maxSubscribers < 1) throw new IllegalArgumentException("Pacing max-subscribers must be at least 1");
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException("Pacing " + name + " must be positive");
    }
}
