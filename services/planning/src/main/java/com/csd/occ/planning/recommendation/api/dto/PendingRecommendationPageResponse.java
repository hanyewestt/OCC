package com.csd.occ.planning.recommendation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated queue of recommendations awaiting a controller decision")
public record PendingRecommendationPageResponse(
        List<PendingRecommendationResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}
