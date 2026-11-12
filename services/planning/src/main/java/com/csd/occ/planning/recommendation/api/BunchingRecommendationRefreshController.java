package com.csd.occ.planning.recommendation.api;

import com.csd.occ.planning.recommendation.api.dto.PendingRecommendationResponse;
import com.csd.occ.planning.recommendation.domain.BunchingRecommendationRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/planning/recommendations")
@Tag(name = "Planning recommendations")
public class BunchingRecommendationRefreshController {
    private final BunchingRecommendationRefreshService recommendations;

    public BunchingRecommendationRefreshController(BunchingRecommendationRefreshService recommendations) {
        this.recommendations = recommendations;
    }

    @PostMapping("/{recommendationId}/refresh")
    @Operation(operationId = "refreshBunchingRecommendation",
            summary = "Refresh a simulation recommendation from current prediction evidence",
            description = "Requires a controller. Can request fresh predictions for an expired simulation. Repeating a refresh on the same predecessor returns its saved revision.")
    @ApiResponse(responseCode = "200", description = "Refreshed recommendation returned for review")
    @ApiResponse(responseCode = "202", description = "New simulation predictions are awaiting detector evaluation", content = @Content)
    @ApiResponse(responseCode = "403", description = "Caller is not a controller")
    @ApiResponse(responseCode = "404", description = "Recommendation not found")
    @ApiResponse(responseCode = "409", description = "Recommendation is ineligible or current evidence is unavailable")
    public ResponseEntity<PendingRecommendationResponse> refresh(@PathVariable UUID recommendationId) {
        return recommendations.refresh(recommendationId)
                .map(record -> ResponseEntity.ok(PendingRecommendationResponse.from(record)))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }
}
