package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One DataMall arrival position. It does not identify a physical bus or driver.")
public record ObservedBusPositionResponse(
        UUID observationId,
        UUID rawObservationId,
        String stopCode,
        String serviceId,
        @Schema(nullable = true) Integer directionId,
        Integer visitOrder,
        @Schema(nullable = true) Instant estimatedArrivalAt,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant fetchedAt,
        boolean stale,
        @Schema(example = "OBSERVATION_ONLY") String classification,
        @Schema(example = "UNVERIFIED") String identityStatus) {
}
