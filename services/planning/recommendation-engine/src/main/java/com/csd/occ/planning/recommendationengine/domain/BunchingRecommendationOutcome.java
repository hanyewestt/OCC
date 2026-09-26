package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;
import java.util.Optional;

public record BunchingRecommendationOutcome(
        ValidatedMitigationRecommendation recommendation,
        Optional<ModelInvocationRecord> modelInvocation) {

    public BunchingRecommendationOutcome {
        Objects.requireNonNull(recommendation, "recommendation is required");
        Objects.requireNonNull(modelInvocation, "modelInvocation is required");
        modelInvocation.ifPresent(invocation -> {
            if (!invocation.recommendationId().equals(recommendation.recommendationId())) {
                throw new IllegalArgumentException("model invocation must belong to the recommendation");
            }
        });
    }
}
