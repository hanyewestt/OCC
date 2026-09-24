package com.csd.occ.ingestion.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ArrivalResponse(UUID observationId, String stopCode, String serviceId, String operator,
        Integer directionId, Integer visitOrder, Instant estimatedArrivalAt, String originCode,
        String destinationCode, Boolean monitored, BigDecimal latitude, BigDecimal longitude,
        String loadCategory, String feature, String vehicleType, Instant fetchedAt, Long ageSeconds,
        UUID referenceVersionId) {
}
