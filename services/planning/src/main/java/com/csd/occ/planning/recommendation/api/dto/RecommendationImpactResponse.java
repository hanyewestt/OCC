package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.SimulatedImpactEstimate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Synthetic before-and-after impact under the documented simulation fixture")
public record RecommendationImpactResponse(
        int headwayBeforeSeconds,
        int headwayAfterSeconds,
        int queueBeforePassengers,
        int queueAfterPassengers,
        BigDecimal passengerMinutesBefore,
        BigDecimal passengerMinutesAfter) {

    public static RecommendationImpactResponse from(SimulatedImpactEstimate impact) {
        return new RecommendationImpactResponse(
                impact.headwayBeforeSeconds(),
                impact.headwayAfterSeconds(),
                impact.queueBeforePassengers(),
                impact.queueAfterPassengers(),
                impact.passengerMinutesBefore(),
                impact.passengerMinutesAfter());
    }
}
