package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable producer-owned facts needed to begin Planning without calling Disruption back. */
public record BunchingEventSnapshot(UUID eventId, UUID bunchingEventId, String classification, UUID demoRunId,
        String serviceId, int directionId, String monitoredStopCode, UUID leadingObservationId,
        UUID followingObservationId, String status, int expectedHeadwaySeconds,
        List<Integer> observedHeadwaySeconds, Instant firstConfirmedAt, List<Instant> confirmingObservationTimes,
        List<UUID> confirmingObservationIds, List<String> evidenceIds, String detectionRuleVersion) {
    public BunchingEventSnapshot {
        observedHeadwaySeconds = observedHeadwaySeconds == null ? null : List.copyOf(observedHeadwaySeconds);
        confirmingObservationTimes = confirmingObservationTimes == null ? null : List.copyOf(confirmingObservationTimes);
        confirmingObservationIds = confirmingObservationIds == null ? null : List.copyOf(confirmingObservationIds);
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }
}
