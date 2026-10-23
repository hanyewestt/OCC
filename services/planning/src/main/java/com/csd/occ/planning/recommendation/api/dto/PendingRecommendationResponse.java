package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Actionable recommendation awaiting a controller decision")
public record PendingRecommendationResponse(
        UUID recommendationId,
        String classification,
        String status,
        String serviceId,
        int directionId,
        String monitoredStopCode,
        Instant detectedAt,
        Instant createdAt,
        int expectedHeadwaySeconds,
        int observedHeadwaySeconds,
        String mitigationType,
        String targetBusReference,
        List<String> affectedBusReferences,
        String controlStopCode,
        int adjustmentSeconds,
        RecommendationExplanationResponse explanation,
        RecommendationImpactResponse expectedImpact) {

    public static PendingRecommendationResponse from(RecommendationRecord record) {
        var event = record.bunchingEvent();
        var recommendation = record.recommendation();
        List<Integer> observed = event.observedHeadwaySeconds();
        return new PendingRecommendationResponse(
                recommendation.recommendationId(),
                record.classification().name(),
                recommendation.status().name(),
                event.serviceId(),
                event.directionId(),
                event.monitoredStopCode(),
                event.firstConfirmedAt(),
                record.createdAt(),
                event.expectedHeadwaySeconds(),
                observed.get(observed.size() - 1),
                recommendation.mitigationType().name(),
                recommendation.targetBusReference(),
                record.mitigationContext().affectedBusReferences(),
                recommendation.controlStopCode(),
                recommendation.adjustmentSeconds(),
                RecommendationExplanationResponse.from(recommendation.explanation()),
                RecommendationImpactResponse.from(recommendation.expectedImpact()));
    }
}
