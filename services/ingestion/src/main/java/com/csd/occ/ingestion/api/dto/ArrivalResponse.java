package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"observationId", "stopCode", "serviceId", "operator", "directionId", "visitOrder",
        "estimatedArrivalAt", "originCode", "destinationCode", "monitored", "latitude", "longitude", "loadCategory",
        "feature", "vehicleType", "fetchedAt", "ageSeconds", "referenceVersionId"})
public record ArrivalResponse(UUID observationId, String stopCode, String serviceId, String operator,
        @Schema(nullable = true) Integer directionId, @Schema(nullable = true) Integer visitOrder,
        @Schema(nullable = true) Instant estimatedArrivalAt, @Schema(nullable = true) String originCode,
        @Schema(nullable = true) String destinationCode, @Schema(nullable = true) Boolean monitored,
        @Schema(nullable = true) BigDecimal latitude, @Schema(nullable = true) BigDecimal longitude,
        @Schema(nullable = true) String loadCategory, @Schema(nullable = true) String feature,
        @Schema(nullable = true) String vehicleType, Instant fetchedAt, @Schema(nullable = true) Long ageSeconds,
        UUID referenceVersionId) {
}
