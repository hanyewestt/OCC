package com.csd.occ.planning.recommendation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated controller decision audit records")
public record RecommendationDecisionPageResponse(
        List<RecommendationDecisionResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}
