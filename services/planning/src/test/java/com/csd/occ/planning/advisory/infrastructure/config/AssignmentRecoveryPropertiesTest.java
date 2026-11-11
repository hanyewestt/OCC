package com.csd.occ.planning.advisory.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssignmentRecoveryPropertiesTest {
    @Test
    void acceptsBoundedRecoverySettings() {
        assertDoesNotThrow(() -> new AssignmentRecoveryProperties(Duration.ofSeconds(5), 25));
    }

    @Test
    void rejectsInvalidIntervalAndBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRecoveryProperties(Duration.ZERO, 25));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRecoveryProperties(Duration.ofSeconds(5), 0));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRecoveryProperties(Duration.ofSeconds(5), 101));
    }
}
