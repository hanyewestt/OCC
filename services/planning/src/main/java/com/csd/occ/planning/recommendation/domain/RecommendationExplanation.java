package com.csd.occ.planning.recommendation.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RecommendationExplanation(
        String rationale,
        String likelyCause,
        BigDecimal diagnosisConfidence,
        List<String> tradeOffs,
        List<String> limitations) {

    public RecommendationExplanation {
        rationale = requireNonBlank(rationale, "rationale");
        likelyCause = requireNonBlank(likelyCause, "likelyCause");
        diagnosisConfidence = null;
        tradeOffs = List.copyOf(tradeOffs);
        if (tradeOffs.isEmpty()) {
            throw new IllegalArgumentException("tradeOffs must not be empty");
        }
        limitations = List.copyOf(limitations);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
