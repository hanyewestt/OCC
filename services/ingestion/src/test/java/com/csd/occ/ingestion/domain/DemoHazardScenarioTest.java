package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DemoHazardScenarioTest {
    @Test
    void repeatedScenariosKeepIdentitiesAndEndWithExplicitRecoveryFacts() {
        var first = DemoHazardScenario.observations(0, Instant.EPOCH, Instant.EPOCH);
        var update = DemoHazardScenario.observations(1, Instant.EPOCH, Instant.EPOCH.plusSeconds(5));
        var end = DemoHazardScenario.observations(2, Instant.EPOCH, Instant.EPOCH.plusSeconds(10));
        assertThat(first).extracting(HazardFeedPort.Fact::kind)
                .containsExactly(HazardKind.FLOOD, HazardKind.RAIL_DISRUPTION, HazardKind.ROADWORKS);
        assertThat(first).extracting(HazardFeedPort.Fact::sourceEventKey)
                .containsExactlyElementsOf(update.stream().map(HazardFeedPort.Fact::sourceEventKey).toList());
        assertThat(end.getFirst().references()).containsExactly(first.getFirst().sourceEventKey());
        assertThat(end.get(1).sourceAction()).isEqualTo("RECOVERED");
        assertThat(end.getLast().effectiveUntil()).isEqualTo(Instant.EPOCH.plusSeconds(10));
    }
}
