package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceBoundsTest {
    @Test
    void rejectsNullAndOversizedCollectionsWithDomainLimitException() {
        assertThatThrownBy(() -> EvidenceBounds.copy(null, 2, "arrivals"))
                .isInstanceOf(EvidenceLimitExceededException.class);
        assertThatThrownBy(() -> EvidenceBounds.copy(List.of(1, 2, 3), 2, "arrivals"))
                .isInstanceOf(EvidenceLimitExceededException.class);
        assertThatThrownBy(() -> EvidenceBounds.copy(Collections.singletonList(null), 2, "arrivals"))
                .isInstanceOf(EvidenceLimitExceededException.class);
    }

    @Test
    void rejectsOversizedOrBlankEvidenceStrings() {
        assertThatThrownBy(() -> EvidenceBounds.copyStrings(List.of("x".repeat(201)), 2, 200, "evidenceIds"))
                .isInstanceOf(EvidenceLimitExceededException.class);
        assertThatThrownBy(() -> EvidenceBounds.copyStrings(List.of(" "), 2, 200, "evidenceIds"))
                .isInstanceOf(EvidenceLimitExceededException.class);
    }
}
