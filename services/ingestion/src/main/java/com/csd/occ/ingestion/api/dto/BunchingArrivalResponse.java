package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BunchingArrivalResponse(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                                      @Schema(nullable = true) String operator,
                                      @Schema(nullable = true) Integer directionId,
                                      @Schema(nullable = true) Integer visitOrder,
                                      @Schema(nullable = true) Integer visitNumber,
                                      @Schema(nullable = true) Instant estimatedArrivalAt,
                                      @Schema(nullable = true) String originCode,
                                      @Schema(nullable = true) String destinationCode,
                                      @Schema(nullable = true) BigDecimal latitude,
                                      @Schema(nullable = true) BigDecimal longitude,
                                      @Schema(nullable = true,
                                              description = "Unique occurrence in the active route topology; null when the stop is absent or repeated")
                                      Integer routeStopSequence) {
}
