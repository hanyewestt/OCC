package com.csd.occ.planning.recommendation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecommendationDecision(
        UUID recommendationId,
        RecommendationDecisionAction action,
        String actorSubject,
        Instant decidedAt) {

    public RecommendationDecision {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(action, "action is required");
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject is required");
        }
        Objects.requireNonNull(decidedAt, "decidedAt is required");
    }
}
