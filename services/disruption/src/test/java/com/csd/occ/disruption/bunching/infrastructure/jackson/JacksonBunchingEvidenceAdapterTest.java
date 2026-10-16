package com.csd.occ.disruption.bunching.infrastructure.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.csd.occ.disruption.bunching.domain.ArrivalSnapshot;
import com.csd.occ.disruption.bunching.domain.BunchingEvaluation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonBunchingEvidenceAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-26T05:00:00Z");

    @Test
    void alertEvidenceRetainsSimulationClassificationAndRunScope() throws Exception {
        UUID demoRunId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        UUID leadingId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();
        var snapshot = new ArrivalSnapshot(rawId, pollId, Optional.empty(), NOW, List.of(
                arrival(leadingId, 1, NOW.plusSeconds(120)),
                arrival(followingId, 2, NOW.plusSeconds(165))), "SIMULATION", demoRunId);
        var group = new BunchingEvaluation.GroupEvaluation(
                new BunchingEvaluation.Group("190", "44251", Optional.of(1)),
                BunchingEvaluation.State.BUNCHING, BunchingEvaluation.Reason.NONE,
                List.of(new BunchingEvaluation.Headway(leadingId, followingId, Duration.ofSeconds(45), true)));
        var evaluation = new BunchingEvaluation(snapshot, NOW.plusSeconds(1), Duration.ofSeconds(90),
                Duration.ofSeconds(180), List.of(group));
        var mapper = JsonMapper.builder().build();
        var evidence = new JacksonBunchingEvidenceAdapter(mapper).encodeAlert(evaluation, group);
        var decoded = mapper.readTree(evidence);

        assertEquals("SIMULATION", decoded.path("snapshot").path("classification").asString());
        assertEquals(demoRunId.toString(), decoded.path("snapshot").path("demoRunId").asString());
        assertEquals(2, decoded.path("snapshot").path("arrivals").size());
    }

    private static ArrivalSnapshot.Arrival arrival(UUID id, int visitOrder, Instant eta) {
        return new ArrivalSnapshot.Arrival(id, "190", "44251", Optional.of(1), visitOrder, Optional.of(eta));
    }
}
