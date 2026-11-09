package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import com.csd.occ.planning.recommendation.domain.CandidateMitigationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Controller decision on a recommendation, with one replacement action per affected bus when rejected")
public record RecommendationDecisionRequest(
        @NotNull RecommendationDecisionAction action,
        CandidateMitigationType replacementAction,
        @Min(1) @Max(120) Integer adjustmentSeconds,
        @Size(max = 1000) String controllerRationale,
        @Size(max = 3) List<@jakarta.validation.Valid ReplacementBusInstructionRequest> replacementInstructions) {

    public List<ReplacementBusInstructionRequest> normalizedReplacements() {
        if (replacementInstructions != null && !replacementInstructions.isEmpty()) return List.copyOf(replacementInstructions);
        if (replacementAction != null && adjustmentSeconds != null)
            return List.of(new ReplacementBusInstructionRequest("", replacementAction, adjustmentSeconds));
        return List.of();
    }

    public RecommendationDecisionRequest {
        if (action == RecommendationDecisionAction.APPROVED
                && (replacementAction != null || adjustmentSeconds != null || controllerRationale != null
                    || replacementInstructions != null && !replacementInstructions.isEmpty())) {
            throw new IllegalArgumentException("approved decisions cannot contain an override");
        }
        if (action == RecommendationDecisionAction.REJECTED
                && ((replacementInstructions == null || replacementInstructions.isEmpty())
                    && (replacementAction == null || adjustmentSeconds == null)
                    || controllerRationale == null || controllerRationale.isBlank())) {
            throw new IllegalArgumentException("rejected decisions require per-bus actions and a rationale");
        }
    }
}
