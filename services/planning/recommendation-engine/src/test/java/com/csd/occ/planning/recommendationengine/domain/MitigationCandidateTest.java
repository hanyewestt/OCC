package com.csd.occ.planning.recommendationengine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MitigationCandidateTest {

    @Test
    void validCandidateContainsOnlyProposedActionAndRationale() {
        var candidate = candidate(CandidateMitigationType.HOLD, 120);

        assertThat(candidate.mitigationType().toMitigationType()).isEqualTo(MitigationType.HOLD);
        assertThat(candidate.targetBusReference()).isEqualTo("SBS5898U");
        assertThat(candidate.controlStopCode()).isEqualTo("44241");
        assertThat(candidate.requestedAdjustmentSeconds()).isEqualTo(120);
        assertThat(candidate.rationale()).contains("approved control stop");
    }

    @Test
    void candidateContractExcludesUnsupportedStandbyDispatch() {
        assertThat(CandidateMitigationType.values())
                .containsExactly(CandidateMitigationType.HOLD, CandidateMitigationType.DWELL_ADJUSTMENT);
    }

    @Test
    void excessiveOrNonPositiveAdjustmentIsRejected() {
        assertThatThrownBy(() -> candidate(CandidateMitigationType.HOLD, 121))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and 120");
        assertThatThrownBy(() -> candidate(CandidateMitigationType.HOLD, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and 120");
    }

    @Test
    void missingOrOversizedTextIsRejected() {
        assertThatThrownBy(() -> new MitigationCandidate(
                CandidateMitigationType.HOLD,
                " ",
                "44241",
                120,
                "Safe rationale"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBusReference is required");
        assertThatThrownBy(() -> new MitigationCandidate(
                CandidateMitigationType.HOLD,
                "SBS5898U",
                "44241",
                120,
                "x".repeat(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale must not exceed 1000 characters");
    }

    private MitigationCandidate candidate(CandidateMitigationType mitigationType, int adjustmentSeconds) {
        return new MitigationCandidate(
                mitigationType,
                "SBS5898U",
                "44241",
                adjustmentSeconds,
                "Hold the follower at the approved control stop.");
    }
}
