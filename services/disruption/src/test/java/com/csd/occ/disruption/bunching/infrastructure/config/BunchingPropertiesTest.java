package com.csd.occ.disruption.bunching.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class BunchingPropertiesTest {
    @Test
    void invalidSettingsFailInsteadOfStartingAnUnboundedOrDisabledRule() {
        var positive = Duration.ofSeconds(90);
        var retryDelay = Duration.ofSeconds(30);
        var connectTimeout = Duration.ofSeconds(2);
        var responseTimeout = Duration.ofSeconds(5);
        URI ingestion = URI.create("http://localhost:8080");
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(Duration.ZERO, positive, positive, 100, ingestion, retryDelay, connectTimeout, responseTimeout));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, Duration.ZERO, positive, 100, ingestion, retryDelay, connectTimeout, responseTimeout));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, positive, Duration.ofNanos(1), 100, ingestion, retryDelay, connectTimeout, responseTimeout));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, positive, positive, 1001, ingestion, retryDelay, connectTimeout, responseTimeout));
    }
}
