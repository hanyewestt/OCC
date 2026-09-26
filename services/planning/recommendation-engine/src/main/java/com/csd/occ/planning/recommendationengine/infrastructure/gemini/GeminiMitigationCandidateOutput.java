package com.csd.occ.planning.recommendationengine.infrastructure.gemini;

import com.csd.occ.planning.recommendationengine.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidate;

public record GeminiMitigationCandidateOutput(
        CandidateMitigationType mitigationType,
        String targetBusReference,
        String controlStopCode,
        int requestedAdjustmentSeconds,
        String rationale) {

    MitigationCandidate toDomain() {
        return new MitigationCandidate(
                mitigationType,
                targetBusReference,
                controlStopCode,
                requestedAdjustmentSeconds,
                rationale);
    }
}
