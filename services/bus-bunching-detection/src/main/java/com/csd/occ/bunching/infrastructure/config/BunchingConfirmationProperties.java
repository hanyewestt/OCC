package com.csd.occ.bunching.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.bunching")
public record BunchingConfirmationProperties(Duration expectedHeadway) {
    public BunchingConfirmationProperties {
        if (expectedHeadway == null || expectedHeadway.isZero() || expectedHeadway.isNegative()
                || expectedHeadway.getSeconds() < 1 || expectedHeadway.getSeconds() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("expected-headway must be a positive whole-second duration fitting an integer");
    }
    public int expectedHeadwaySeconds() { return Math.toIntExact(expectedHeadway.getSeconds()); }
}
