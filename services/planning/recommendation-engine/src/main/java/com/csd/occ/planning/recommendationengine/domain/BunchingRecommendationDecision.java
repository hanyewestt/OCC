package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;
import java.util.Optional;

public record BunchingRecommendationDecision(
        ValidatedMitigationRecommendation recommendation,
        ValidatedMitigationContext mitigationContext,
        Optional<ModelInvocationRecord> modelInvocation) {

    public BunchingRecommendationDecision {
        Objects.requireNonNull(recommendation, "recommendation is required");
        Objects.requireNonNull(mitigationContext, "mitigationContext is required");
        Objects.requireNonNull(modelInvocation, "modelInvocation is required");
        modelInvocation.ifPresent(invocation -> {
            if (!invocation.recommendationId().equals(recommendation.recommendationId())) {
                throw new IllegalArgumentException("model invocation must belong to the recommendation");
            }
        });
    }
}
