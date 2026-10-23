package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Persisted controller decision; approval does not itself dispatch a bus instruction")
public record RecommendationDecisionResponse(
        UUID recommendationId,
        RecommendationDecisionAction action,
        String actorSubject,
        List<ControllerBusInstructionResponse> overrideInstructions,
        String controllerRationale,
        Instant decidedAt) {
    public static RecommendationDecisionResponse from(RecommendationDecision decision) {
        return new RecommendationDecisionResponse(
                decision.recommendationId(), decision.action(), decision.actorSubject(),
                decision.overrideInstructions().stream().map(ControllerBusInstructionResponse::from).toList(),
                decision.controllerRationale(), decision.decidedAt());
    }
}
