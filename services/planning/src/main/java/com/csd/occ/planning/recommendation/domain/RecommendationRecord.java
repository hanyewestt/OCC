package com.csd.occ.planning.recommendation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecommendationRecord(
        UUID idempotencyKey,
        String requestHash,
        UUID requestId,
        SourceClassification classification,
        ConfirmedBunchingEvent bunchingEvent,
        BunchingRootCauseDiagnosis diagnosis,
        ValidatedMitigationContext mitigationContext,
        ValidatedMitigationRecommendation recommendation,
        Instant createdAt) {

    public RecommendationRecord {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 value");
        }
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(bunchingEvent, "bunchingEvent is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");
        Objects.requireNonNull(mitigationContext, "mitigationContext is required");
        Objects.requireNonNull(recommendation, "recommendation is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (!idempotencyKey.equals(recommendation.recommendationId())) {
            throw new IllegalArgumentException("recommendationId must equal idempotencyKey");
        }
    }
}
