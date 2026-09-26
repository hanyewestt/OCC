package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;

public record BunchingRecommendationDecision(
        ValidatedMitigationRecommendation recommendation,
        ValidatedMitigationContext mitigationContext) {

    public BunchingRecommendationDecision {
        Objects.requireNonNull(recommendation, "recommendation is required");
        Objects.requireNonNull(mitigationContext, "mitigationContext is required");
    }
}
