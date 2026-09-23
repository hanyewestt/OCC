package com.csd.occ.planning.recommendationengine.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ValidatedMitigationRecommendation(
        UUID recommendationId,
        IncidentType incidentType,
        RecommendationStatus status,
        MitigationType mitigationType,
        String targetBusReference,
        String controlStopCode,
        int adjustmentSeconds,
        SimulatedImpactEstimate expectedImpact,
        List<String> evidenceIds) {

    public ValidatedMitigationRecommendation {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(incidentType, "incidentType is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(mitigationType, "mitigationType is required");
        targetBusReference = requireNonBlank(targetBusReference, "targetBusReference");
        controlStopCode = requireNonBlank(controlStopCode, "controlStopCode");
        if (adjustmentSeconds < 1 || adjustmentSeconds > 120) {
            throw new IllegalArgumentException("adjustmentSeconds must be between one and 120");
        }
        Objects.requireNonNull(expectedImpact, "expectedImpact is required");
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
