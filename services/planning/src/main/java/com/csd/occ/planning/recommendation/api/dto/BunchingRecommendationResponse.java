package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.diagnosis.domain.DiagnosisSummaryPolicy;
import com.csd.occ.planning.recommendation.domain.IncidentType;
import com.csd.occ.planning.recommendation.domain.MitigationType;
import com.csd.occ.planning.recommendation.domain.RecommendationStatus;
import com.csd.occ.planning.recommendation.domain.SourceClassification;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationRecommendation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "One validated mitigation recommendation for controller review")
public record BunchingRecommendationResponse(
        UUID requestId,
        UUID recommendationId,
        SourceClassification classification,
        IncidentType incidentType,
        RecommendationStatus status,
        MitigationType mitigationType,
        String targetBusReference,
        String controlStopCode,
        int adjustmentSeconds,
        RecommendationExplanationResponse explanation,
        RecommendationImpactResponse expectedImpact,
        List<String> evidenceIds) {

    public static BunchingRecommendationResponse from(
            UUID requestId,
            SourceClassification classification,
            ValidatedMitigationRecommendation recommendation,
            DiagnosisSummaryPolicy.Summary diagnosisSummary) {
        return new BunchingRecommendationResponse(
                requestId,
                recommendation.recommendationId(),
                classification,
                recommendation.incidentType(),
                recommendation.status(),
                recommendation.mitigationType(),
                recommendation.targetBusReference(),
                recommendation.controlStopCode(),
                recommendation.adjustmentSeconds(),
                RecommendationExplanationResponse.from(recommendation.explanation(), diagnosisSummary),
                RecommendationImpactResponse.from(recommendation.expectedImpact()),
                recommendation.evidenceIds());
    }

    public static BunchingRecommendationResponse from(
            UUID requestId,
            SourceClassification classification,
            ValidatedMitigationRecommendation recommendation) {
        return new BunchingRecommendationResponse(requestId, recommendation.recommendationId(), classification,
                recommendation.incidentType(), recommendation.status(), recommendation.mitigationType(),
                recommendation.targetBusReference(), recommendation.controlStopCode(), recommendation.adjustmentSeconds(),
                RecommendationExplanationResponse.from(recommendation.explanation()),
                RecommendationImpactResponse.from(recommendation.expectedImpact()), recommendation.evidenceIds());
    }
}
