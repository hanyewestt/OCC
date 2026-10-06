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
        if (likelyCause.length() > 1_000) {
            throw new IllegalArgumentException("likelyCause must be at most 1000 characters");
        }
        Objects.requireNonNull(confidence, "confidence is required");
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty() || evidenceIds.size() > 100
                || evidenceIds.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 200)) {
            throw new IllegalArgumentException("evidenceIds must contain 1..100 non-blank values of at most 200 characters");
        }
        limitations = List.copyOf(limitations);
        if (limitations.size() > 20
                || limitations.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 500)) {
            throw new IllegalArgumentException("limitations must contain at most 20 non-blank values of at most 500 characters");
        }
    }
}
