package com.csd.occ.planning.recommendation.domain;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationDecisionService {
    private final RecommendationDecisionPort decisions;
    private final RecommendationRecordPort recommendations;
    private final BusInstructionService busInstructions;
    private final Clock clock;

    public RecommendationDecisionService(
            RecommendationDecisionPort decisions,
            RecommendationRecordPort recommendations,
            BusInstructionService busInstructions,
            Clock clock) {
        this.decisions = decisions;
        this.recommendations = recommendations;
        this.busInstructions = busInstructions;
        this.clock = clock;
    }

    @Transactional
    public RecommendationDecision decide(UUID recommendationId, RecommendationDecisionAction action,
            String actorSubject, List<ControllerBusInstruction> overrideInstructions, String controllerRationale) {
        RecommendationRecord recommendation = recommendations.findByIdempotencyKey(recommendationId)
                .orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
        if (recommendation.classification() != SourceClassification.SIMULATION
                || recommendation.recommendation().status() != RecommendationStatus.RECOMMENDED) {
            throw new RecommendationNotActionableException(recommendationId);
        }
        RecommendationDecision requested = new RecommendationDecision(
                recommendationId, action, actorSubject, overrideInstructions, controllerRationale, clock.instant());
        RecommendationDecision persisted = decisions.recordIfAbsent(requested)
                .orElseGet(() -> decisions.findByRecommendationId(recommendationId)
                        .orElseThrow(() -> new IllegalStateException("Decision was not persisted")));
        if (persisted.action() != action
                || !persisted.overrideInstructions().equals(requested.overrideInstructions())
                || !Objects.equals(persisted.controllerRationale(), requested.controllerRationale())) {
            throw new RecommendationDecisionConflictException(recommendationId);
        }
        busInstructions.issueFor(recommendation, persisted);
        return persisted;
    }

    public java.util.Optional<RecommendationDecision> findDecision(UUID recommendationId) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        if (recommendations.findByIdempotencyKey(recommendationId).isEmpty()) {
            throw new RecommendationNotFoundException(recommendationId);
        }
        return decisions.findByRecommendationId(recommendationId);
    }

    public List<RecommendationDecision> findDecisions(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page and size are outside the supported range");
        }
        return decisions.findAll(page * size, size);
    }

    public long countDecisions() {
        return decisions.count();
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
