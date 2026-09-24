package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record BusArrivalPrediction(
        String serviceId,
        String operator,
        Integer directionId,
        int visitOrder,
        String originCode,
        String destinationCode,
        Instant estimatedArrivalAt,
        Boolean monitored,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer visitNumber,
        String loadCategory,
        String feature,
        String vehicleType) {
}
