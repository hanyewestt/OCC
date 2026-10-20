package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Controller's one-click decision on a recommendation")
public record RecommendationDecisionRequest(
        @NotNull RecommendationDecisionAction action) {
}
