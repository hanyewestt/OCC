package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidatedMitigationContextTest {
    @Test
    void retainsAllBusesInALargerGroup() {
        var context = context(List.of("bus-1", "bus-2", "bus-3", "bus-4"));

        assertThat(context.affectedBusReferences()).containsExactly("bus-1", "bus-2", "bus-3", "bus-4");
        assertThat(context.followingBusReference()).isEqualTo("bus-4");
    }

    @Test
    void rejectsDuplicateBusesAndMissingTarget() {
        assertThatThrownBy(() -> context(List.of("bus-1", "bus-4", "bus-4")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context(List.of("bus-1", "bus-2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOversizedGroup() {
        var buses = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> "bus-" + i).toList();
        assertThatThrownBy(() -> context(buses)).isInstanceOf(IllegalArgumentException.class);
    }

    private ValidatedMitigationContext context(List<String> buses) {
        return new ValidatedMitigationContext("bus-4", buses, "44251", FollowingBusState.READY_TO_DEPART,
                120, 75, CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED, List.of("evidence"));
    }
}
