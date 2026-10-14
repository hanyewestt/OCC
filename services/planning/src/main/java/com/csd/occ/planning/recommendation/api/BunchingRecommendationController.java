package com.csd.occ.planning.recommendation.api;

import com.csd.occ.planning.recommendation.api.dto.BunchingRecommendationResponse;
import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/planning/recommendations", "/internal/v1/planning/recommendations"})
@Tag(name = "Planning recommendations")
public class BunchingRecommendationController {
    private final RecommendationRecordPort records;

    public BunchingRecommendationController(RecommendationRecordPort records) {
        this.records = records;
    }

    @GetMapping("/by-disruption-event/{bunchingEventId}")
    @Operation(operationId = "getBunchingRecommendation",
            summary = "Get the recommendation for a disruption event")
    @ApiResponse(responseCode = "200", description = "Recommendation returned")
    @ApiResponse(responseCode = "404", description = "No recommendation is available for the event")
    public ResponseEntity<BunchingRecommendationResponse> findByDisruptionEventId(
            @PathVariable UUID bunchingEventId) {
        return records.findByBunchingEventId(bunchingEventId)
                .map(record -> BunchingRecommendationResponse.from(
                        record.requestId(), record.classification(), record.recommendation()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
