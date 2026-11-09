package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import com.csd.occ.planning.recommendation.domain.CandidateMitigationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Controller decision on a recommendation, with one typed replacement action when rejected")
public record RecommendationDecisionRequest(
        @NotNull RecommendationDecisionAction action,
        CandidateMitigationType replacementAction,
        @Min(1) @Max(120) Integer adjustmentSeconds,
        @Size(max = 1000) String controllerRationale) {

    public RecommendationDecisionRequest {
        if (action == RecommendationDecisionAction.APPROVED
                && (replacementAction != null || adjustmentSeconds != null || controllerRationale != null)) {
            throw new IllegalArgumentException("approved decisions cannot contain an override");
        }
        if (action == RecommendationDecisionAction.REJECTED
                && (replacementAction == null || adjustmentSeconds == null || controllerRationale == null || controllerRationale.isBlank())) {
            throw new IllegalArgumentException("rejected decisions require a typed action, duration and rationale");
        }
    }
}
