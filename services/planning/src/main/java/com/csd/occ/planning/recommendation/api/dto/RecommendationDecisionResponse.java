package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Persisted controller decision; approval does not itself dispatch a bus instruction")
public record RecommendationDecisionResponse(
        UUID recommendationId,
        RecommendationDecisionAction action,
        Instant decidedAt) {
    public static RecommendationDecisionResponse from(RecommendationDecision decision) {
        return new RecommendationDecisionResponse(
                decision.recommendationId(), decision.action(), decision.decidedAt());
    }
}
