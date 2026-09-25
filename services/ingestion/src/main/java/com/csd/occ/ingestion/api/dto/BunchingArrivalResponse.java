package com.csd.occ.ingestion.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BunchingArrivalResponse(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                                      Integer directionId, Integer visitOrder, Instant estimatedArrivalAt,
                                      String originCode, String destinationCode, BigDecimal latitude,
                                      BigDecimal longitude) {
}
