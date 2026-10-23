package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Controller decision on a recommendation, with replacement instructions when rejected")
public record RecommendationDecisionRequest(
        @NotNull RecommendationDecisionAction action,
        @Size(max = 3) List<@Valid ControllerBusInstructionRequest> overrideInstructions,
        @Size(max = 1000) String controllerRationale) {

    public RecommendationDecisionRequest {
        overrideInstructions = overrideInstructions == null ? List.of() : List.copyOf(overrideInstructions);
        if (action == RecommendationDecisionAction.APPROVED
                && (!overrideInstructions.isEmpty() || controllerRationale != null)) {
            throw new IllegalArgumentException("approved decisions cannot contain an override");
        }
        if (action == RecommendationDecisionAction.REJECTED
                && (overrideInstructions.isEmpty() || controllerRationale == null || controllerRationale.isBlank())) {
            throw new IllegalArgumentException("rejected decisions require instructions and rationale");
        }
    }
}
