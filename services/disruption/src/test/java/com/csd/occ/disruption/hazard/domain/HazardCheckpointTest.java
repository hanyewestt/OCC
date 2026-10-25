package com.csd.occ.disruption.hazard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HazardCheckpointTest {
    @Test
    void comparesPollRunIdsUsingPostgresUuidOrderingWhenCompletionTimesMatch() {
        Instant completedAt = Instant.parse("2026-10-25T10:00:00Z");
        HazardCheckpoint checkpoint = new HazardCheckpoint();
        checkpoint.advance(poll("7fffffff-0000-0000-0000-000000000000", completedAt), Duration.ofMinutes(1));

        assertThat(checkpoint.isBefore(poll("80000000-0000-0000-0000-000000000000", completedAt)))
                .isTrue();
    }

    private static HazardPollPort.Poll poll(String pollRunId, Instant completedAt) {
        return new HazardPollPort.Poll(UUID.fromString(pollRunId), "source", "scope", "SUCCESS", true,
                "OBSERVATION_ONLY", null, completedAt, List.of());
    }
}
