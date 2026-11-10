package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record DemoBunchingAlert(UUID eventId, UUID bunchingEventId, String action, String serviceId, String stopCode, int directionId,
                                Instant detectedAt, String classification, UUID leadingObservationId,
                                UUID followingObservationId, List<UUID> affectedObservationIds, String evidence) {
    public DemoBunchingAlert { affectedObservationIds = affectedObservationIds == null ? List.of() : List.copyOf(affectedObservationIds); }
}
