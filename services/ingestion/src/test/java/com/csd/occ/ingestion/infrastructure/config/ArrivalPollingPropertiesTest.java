package com.csd.occ.ingestion.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ArrivalPollingPropertiesTest {
    @Test
    void credentialsAreRedactedAndUnsafePollingSettingsAreRejected() {
        var url = URI.create("http://127.0.0.1:8080");
        var timeout = Duration.ofSeconds(10);
        var lease = Duration.ofSeconds(45);
        assertFalse(new ArrivalPollingProperties(url, "synthetic-secret", timeout, lease, 4096, 120)
                .toString().contains("synthetic-secret"));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(url, "", timeout, lease, 4096, 120));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(url, "synthetic", Duration.ofNanos(1), lease, 4096, 120));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(url, "synthetic", timeout, timeout, 4096, 120));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(url, "synthetic", timeout, lease, 0, 120));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(url, "synthetic", timeout, lease, 4096, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalPollingProperties(URI.create("http://user:secret@host"), "synthetic", timeout, lease, 4096, 120));
    }
}
