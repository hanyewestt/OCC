package com.csd.occ.planning.recommendationengine.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ConfirmedBunchingEvent(
        UUID bunchingEventId,
        String serviceId,
        int directionId,
        String monitoredStopCode,
        BunchingEventStatus status,
        int expectedHeadwaySeconds,
        List<Integer> observedHeadwaySeconds,
        Instant firstConfirmedAt,
        List<String> evidenceIds) {

    public ConfirmedBunchingEvent {
        Objects.requireNonNull(bunchingEventId, "bunchingEventId is required");
        serviceId = requireNonBlank(serviceId, "serviceId");
        if (directionId < 1) {
            throw new IllegalArgumentException("directionId must be positive");
        }
        monitoredStopCode = requireNonBlank(monitoredStopCode, "monitoredStopCode");
        Objects.requireNonNull(status, "status is required");
        if (expectedHeadwaySeconds < 1) {
            throw new IllegalArgumentException("expectedHeadwaySeconds must be positive");
        }
        observedHeadwaySeconds = List.copyOf(observedHeadwaySeconds);
        if (observedHeadwaySeconds.isEmpty() || observedHeadwaySeconds.stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("observedHeadwaySeconds must contain non-negative values");
        }
        Objects.requireNonNull(firstConfirmedAt, "firstConfirmedAt is required");
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
