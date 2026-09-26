package com.csd.occ.operations.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveMapResponse(List<Observation> items, int page, int size, long totalItems, int totalPages) {
    public record Observation(UUID observationId, String stopCode, String serviceId, Integer directionId,
            Integer visitOrder, Instant estimatedArrivalAt, BigDecimal latitude, BigDecimal longitude,
            Instant fetchedAt, boolean stale, String classification, String identityStatus) { }
}
