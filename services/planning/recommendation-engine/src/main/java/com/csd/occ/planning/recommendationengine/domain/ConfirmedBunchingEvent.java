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
        requireLength(serviceId, 20, "serviceId");
        if (directionId < 1) {
            throw new IllegalArgumentException("directionId must be positive");
        }
        monitoredStopCode = requireNonBlank(monitoredStopCode, "monitoredStopCode");
        requireLength(monitoredStopCode, 12, "monitoredStopCode");
        Objects.requireNonNull(status, "status is required");
        if (expectedHeadwaySeconds < 1) {
            throw new IllegalArgumentException("expectedHeadwaySeconds must be positive");
        }
        observedHeadwaySeconds = List.copyOf(observedHeadwaySeconds);
        if (observedHeadwaySeconds.size() < 2 || observedHeadwaySeconds.size() > 10
                || observedHeadwaySeconds.stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("observedHeadwaySeconds must contain 2..10 non-negative values");
        }
        Objects.requireNonNull(firstConfirmedAt, "firstConfirmedAt is required");
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty() || evidenceIds.size() > 100
                || evidenceIds.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 200)) {
            throw new IllegalArgumentException("evidenceIds must contain 1..100 non-blank values of at most 200 characters");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static void requireLength(String value, int maximum, String fieldName) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maximum + " characters");
        }
    }
}
