package com.csd.occ.planning.recommendation.infrastructure.gemini;

import com.csd.occ.planning.recommendation.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendation.domain.MitigationCandidate;

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
