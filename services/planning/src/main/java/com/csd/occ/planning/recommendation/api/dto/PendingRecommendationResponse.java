package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationRecord;
import com.csd.occ.planning.diagnosis.domain.DiagnosisSummaryPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Actionable recommendation awaiting a controller decision")
public record PendingRecommendationResponse(
        UUID recommendationId,
        UUID bunchingEventId,
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
        RecommendationImpactResponse expectedImpact,
        @Schema(description = "Source observation for a refreshed recommendation", nullable = true)
        UUID revisionRawObservationId,
        @Schema(description = "Current prediction gaps in seconds between successive affected buses; empty for the original review")
        List<Long> currentPredictionGaps,
        @Schema(description = "Historical confirmation headways in seconds, in confirmation order")
        List<Integer> confirmationGaps,
        @Schema(description = "Ordered suggestions tied to the reviewed snapshot; empty for older recommendations")
        List<SuggestedBusOutcomeResponse> suggestedOutcomes) {

    public record SuggestedBusOutcomeResponse(
            String busReference,
            @Schema(nullable = true, description = "HOLD or DWELL_ADJUSTMENT; null for no instruction") String mitigationType,
            @Schema(description = "Proposed delay in seconds, or zero for no instruction") int adjustmentSeconds,
            @Schema(nullable = true, description = "Reason for suggesting no instruction") String reason) {}

    public static PendingRecommendationResponse from(RecommendationRecord record) {
        return from(record, null);
    }

    public static PendingRecommendationResponse from(RecommendationRecord record,
            DiagnosisSummaryPolicy.Summary diagnosisSummary) {
        var event = record.bunchingEvent();
        var recommendation = record.recommendation();
        List<Integer> observed = event.observedHeadwaySeconds();
        return new PendingRecommendationResponse(
                recommendation.recommendationId(),
                event.bunchingEventId(),
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
                RecommendationExplanationResponse.from(recommendation.explanation(), diagnosisSummary),
                RecommendationImpactResponse.from(recommendation.expectedImpact()),
                record.revisionRawObservationId(),
                record.mitigationContext().currentPredictionGaps(),
                observed,
                recommendation.suggestedOutcomes().stream().map(outcome -> new SuggestedBusOutcomeResponse(
                        outcome.busReference(), outcome.mitigationType() == null ? null : outcome.mitigationType().name(),
                        outcome.adjustmentSeconds(), outcome.reason())).toList());
    }
}
