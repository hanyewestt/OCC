package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BunchingArrivalResponse(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                                      @Schema(nullable = true) String operator,
                                      Integer directionId,
                                      Integer visitOrder,
                                      @Schema(nullable = true) Integer visitNumber,
                                      Instant estimatedArrivalAt,
                                      String originCode,
                                      String destinationCode,
                                      BigDecimal latitude,
                                      BigDecimal longitude,
                                      @Schema(nullable = true,
                                              description = "Unique occurrence in the active route topology; null when the stop is absent or repeated")
                                      Integer routeStopSequence) {
}
