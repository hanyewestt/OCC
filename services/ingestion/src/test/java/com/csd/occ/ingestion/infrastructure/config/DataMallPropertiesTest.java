package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DataMallPropertiesTest {
    @Test
    void rejectsTrafficIncidentPageSizeOutsideSupportedBounds() {
        assertThatThrownBy(() -> properties(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traffic-incident-page-size");
        assertThatThrownBy(() -> properties(10_001, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traffic-incident-page-size");
    }

    @Test
    void rejectsTrafficIncidentPageBudgetAboveSafePerPollLimit() {
        assertThatThrownBy(() -> properties(1_000, 51))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50000 records per poll");
    }

    @Test
    void rejectsResponseByteLimitsOutsideSupportedBounds() {
        assertThatThrownBy(() -> properties(500, 10, 1_023))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-response-bytes");
        assertThatThrownBy(() -> properties(500, 10, 20_000_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-response-bytes");
    }

    private DataMallProperties properties(int pageSize, int maxPages) {
        return properties(pageSize, maxPages, 5_000_000);
    }

    private DataMallProperties properties(int pageSize, int maxPages, int maxResponseBytes) {
        return new DataMallProperties(URI.create("https://example.test"), "synthetic-key",
                Duration.ofSeconds(1), Duration.ofSeconds(2), pageSize, maxPages, maxResponseBytes);
    }
}
