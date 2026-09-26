package com.csd.occ.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LiveMapPort {
    Page observations(String serviceId, int page, int size);
    record Page(List<Observation> items, int page, int size, long totalItems, int totalPages) { }
    record Observation(UUID observationId, String stopCode, String serviceId, Integer directionId,
            Integer visitOrder, Instant estimatedArrivalAt, BigDecimal latitude, BigDecimal longitude,
            Instant fetchedAt, boolean stale) { }
}
