package com.csd.occ.operations.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InferredTrackPageResponse(List<Track> items, int page, int size, long totalItems, int totalPages) {
    public record Track(UUID trackId, String displayLabel, String serviceId, int directionId, String operator,
            double latitude, double longitude, double routeProgressMeters, Instant firstSeenAt, Instant lastSeenAt,
            String status, double confidence, int confirmations, List<UUID> observationIds,
            String classification, String identityStatus, boolean directiveEligible) { }
}
