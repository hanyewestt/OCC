package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.DemoRecommendationDetails;
import com.csd.occ.ingestion.domain.DemoRecommendationImpact;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "Read-only recommendation generated from one simulation bunching event")
public record DemoRecommendationResponse(UUID recommendationId, String classification, String status,
                                         String mitigationType, String targetBusReference, String controlStopCode,
                                         int adjustmentSeconds, String rationale, String likelyCause,
                                         BigDecimal diagnosisConfidence, List<String> tradeOffs,
                                         List<String> limitations, ImpactResponse expectedImpact) {
    public static DemoRecommendationResponse from(DemoRecommendationDetails value) {
        DemoRecommendationImpact impact = value.expectedImpact();
        return new DemoRecommendationResponse(value.recommendationId(), value.classification(), value.status(),
                value.mitigationType(), value.targetBusReference(), value.controlStopCode(), value.adjustmentSeconds(),
                value.rationale(), value.likelyCause(), value.diagnosisConfidence(), value.tradeOffs(),
                value.limitations(), new ImpactResponse(impact.headwayBeforeSeconds(), impact.headwayAfterSeconds(),
                        impact.queueBeforePassengers(), impact.queueAfterPassengers(), impact.passengerMinutesBefore(),
                        impact.passengerMinutesAfter()));
    }

    public record ImpactResponse(int headwayBeforeSeconds, int headwayAfterSeconds, int queueBeforePassengers,
                                 int queueAfterPassengers, BigDecimal passengerMinutesBefore,
                                 BigDecimal passengerMinutesAfter) {}
}
