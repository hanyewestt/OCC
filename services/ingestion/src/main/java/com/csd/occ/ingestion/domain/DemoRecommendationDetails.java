package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DemoRecommendationDetails(UUID recommendationId, String classification, String status,
                                        String mitigationType, String targetBusReference, String controlStopCode,
                                        int adjustmentSeconds, String rationale, String likelyCause,
                                        BigDecimal diagnosisConfidence, List<String> tradeOffs,
                                        List<String> limitations, DemoRecommendationImpact expectedImpact) {
    public DemoRecommendationDetails {
        tradeOffs = List.copyOf(tradeOffs);
        limitations = List.copyOf(limitations);
    }
}
