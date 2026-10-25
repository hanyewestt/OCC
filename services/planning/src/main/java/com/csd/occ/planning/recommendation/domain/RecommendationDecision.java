package com.csd.occ.planning.recommendation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RecommendationDecision(
        UUID recommendationId,
        RecommendationDecisionAction action,
        String actorSubject,
        List<ControllerBusInstruction> overrideInstructions,
        String controllerRationale,
        Instant decidedAt) {

    public RecommendationDecision {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(action, "action is required");
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject is required");
        }
        overrideInstructions = List.copyOf(overrideInstructions);
        if (action == RecommendationDecisionAction.APPROVED) {
            if (!overrideInstructions.isEmpty() || controllerRationale != null) {
                throw new IllegalArgumentException("approved decisions cannot contain an override");
            }
        } else {
            boolean legacyDecisionWithoutOverride = overrideInstructions.isEmpty() && controllerRationale == null;
            if (!legacyDecisionWithoutOverride && (overrideInstructions.isEmpty() || overrideInstructions.size() > 3)) {
                throw new IllegalArgumentException("rejected decisions require 1..3 bus instructions");
            }
            if (!legacyDecisionWithoutOverride && (controllerRationale == null || controllerRationale.isBlank())) {
                throw new IllegalArgumentException("controllerRationale is required for rejected decisions");
            }
            if (controllerRationale != null) controllerRationale = controllerRationale.trim();
            if (controllerRationale != null && controllerRationale.length() > 1000) {
                throw new IllegalArgumentException("controllerRationale must be at most 1000 characters");
            }
            long distinctBusReferences = overrideInstructions.stream()
                    .map(ControllerBusInstruction::busReference)
                    .distinct()
                    .count();
            if (distinctBusReferences != overrideInstructions.size()) {
                throw new IllegalArgumentException("override instructions require unique bus references");
            }
        }
        Objects.requireNonNull(decidedAt, "decidedAt is required");
    }
}
