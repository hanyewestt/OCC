package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;

public record MitigationCandidateRequest(
        SourceClassification classification,
        ConfirmedBunchingEvent bunchingEvent,
        BunchingRootCauseDiagnosis diagnosis,
        ValidatedMitigationContext mitigationContext) {

    public MitigationCandidateRequest {
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(bunchingEvent, "bunchingEvent is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");
        Objects.requireNonNull(mitigationContext, "mitigationContext is required");
    }
}
