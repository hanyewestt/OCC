package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record LiveBunchingAlert(UUID eventId, UUID alertId, String action, String classification, String serviceId,
                                String stopCode, int directionId, Instant detectedAt, Instant fetchedAt,
                                UUID rawObservationId, UUID pollRunId, String evidence, List<UUID> affectedObservationIds,
                                UUID demoRunId) {
    public LiveBunchingAlert { affectedObservationIds = affectedObservationIds == null ? List.of() : List.copyOf(affectedObservationIds); }
}
