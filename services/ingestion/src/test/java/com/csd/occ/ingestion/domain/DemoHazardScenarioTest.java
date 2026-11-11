package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DemoHazardScenarioTest {
    @Test
    void everyStepKeepsTheSameHazardsActiveUntilTheirOneHourExpiry() {
        var startedAt = Instant.EPOCH;
        var steps = IntStream.range(0, DemoReplayScenario.STEP_COUNT)
                .mapToObj(step -> DemoHazardScenario.observations(step, startedAt, startedAt.plusSeconds(5L * step)))
                .toList();
        var first = steps.getFirst();
        assertThat(first).extracting(HazardFeedPort.Fact::kind)
                .containsExactly(HazardKind.FLOOD, HazardKind.RAIL_DISRUPTION, HazardKind.ROADWORKS);
        var expiresAt = startedAt.plusSeconds(3600);
        for (var facts : steps) {
            assertThat(facts).extracting(HazardFeedPort.Fact::sourceEventKey)
                    .containsExactlyElementsOf(first.stream().map(HazardFeedPort.Fact::sourceEventKey).toList());
            assertThat(facts).extracting(HazardFeedPort.Fact::sourceAction).containsOnly("OBSERVED");
            assertThat(facts).allSatisfy(fact -> assertThat(fact.references()).isEmpty());
            assertThat(facts.getFirst().effectiveUntil()).isEqualTo(expiresAt);
            assertThat(facts.getLast().effectiveUntil()).isEqualTo(expiresAt);
        }
    }
}
