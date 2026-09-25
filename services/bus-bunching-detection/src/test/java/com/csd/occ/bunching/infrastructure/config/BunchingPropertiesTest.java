package com.csd.occ.bunching.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class BunchingPropertiesTest {
    @Test
    void invalidSettingsFailInsteadOfStartingAnUnboundedOrDisabledRule() {
        var positive = Duration.ofSeconds(90);
        URI ingestion = URI.create("http://localhost:8080");
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(Duration.ZERO, positive, positive, 100, ingestion));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, Duration.ZERO, positive, 100, ingestion));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, positive, Duration.ofNanos(1), 100, ingestion));
        assertThrows(IllegalArgumentException.class, () -> new BunchingProperties(positive, positive, positive, 1001, ingestion));
    }
}
