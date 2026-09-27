package com.csd.occ.planning.recommendationengine.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BunchingRootCauseDiagnosis(
        UUID diagnosisId,
        String likelyCause,
        BigDecimal confidence,
        List<String> evidenceIds,
        List<String> limitations) {

    public BunchingRootCauseDiagnosis {
        Objects.requireNonNull(diagnosisId, "diagnosisId is required");
        if (likelyCause == null || likelyCause.isBlank()) {
            throw new IllegalArgumentException("likelyCause is required");
        }
        Objects.requireNonNull(confidence, "confidence is required");
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
        limitations = List.copyOf(limitations);
    }
}
