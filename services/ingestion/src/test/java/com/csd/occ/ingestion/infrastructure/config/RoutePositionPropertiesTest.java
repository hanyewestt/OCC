package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoutePositionPropertiesTest {
    @Test
    void rejectsInvalidClusterTolerancePollAgeAndSpeedCap() {
        assertThatThrownBy(() -> new RoutePositionProperties(0, 2, 16.7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cluster-tolerance-meters");
        assertThatThrownBy(() -> new RoutePositionProperties(150, 0, 16.7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-poll-age-cycles");
        assertThatThrownBy(() -> new RoutePositionProperties(150, 2, 40.1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("speed-cap-meters-per-second");
    }
}
