package com.csd.occ.planning.recommendation.domain;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecommendationDecisionService {
    private final RecommendationDecisionPort decisions;
    private final RecommendationRecordPort recommendations;
    private final Clock clock;

    public RecommendationDecisionService(
            RecommendationDecisionPort decisions,
            RecommendationRecordPort recommendations,
            Clock clock) {
        this.decisions = decisions;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    public RecommendationDecision decide(UUID recommendationId, RecommendationDecisionAction action,
            String actorSubject) {
        RecommendationRecord recommendation = recommendations.findByIdempotencyKey(recommendationId)
                .orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
        if (recommendation.classification() != SourceClassification.SIMULATION
                || recommendation.recommendation().status() != RecommendationStatus.RECOMMENDED) {
            throw new RecommendationNotActionableException(recommendationId);
        }
        RecommendationDecision requested = new RecommendationDecision(
                recommendationId, action, actorSubject, clock.instant());
        RecommendationDecision persisted = decisions.recordIfAbsent(requested)
                .orElseGet(() -> decisions.findByRecommendationId(recommendationId)
                        .orElseThrow(() -> new IllegalStateException("Decision was not persisted")));
        if (persisted.action() != action) {
            throw new RecommendationDecisionConflictException(recommendationId);
        }
        return persisted;
    }

    public java.util.Optional<RecommendationDecision> findDecision(UUID recommendationId) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        if (recommendations.findByIdempotencyKey(recommendationId).isEmpty()) {
            throw new RecommendationNotFoundException(recommendationId);
        }
        return decisions.findByRecommendationId(recommendationId);
    }

    public static class RecommendationNotFoundException extends RuntimeException {
        public RecommendationNotFoundException(UUID recommendationId) {
            super("Recommendation not found: " + recommendationId);
        }
    }

    public static class RecommendationDecisionConflictException extends RuntimeException {
        public RecommendationDecisionConflictException(UUID recommendationId) {
            super("Recommendation already has a different decision: " + recommendationId);
        }
    }

    public static class RecommendationNotActionableException extends RuntimeException {
        public RecommendationNotActionableException(UUID recommendationId) {
            super("Recommendation is not eligible for a simulation decision: " + recommendationId);
        }
    }
}
