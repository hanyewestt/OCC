package com.csd.occ.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BunchingEventFacts(UUID eventId, UUID bunchingEventId, String classification, UUID demoRunId,
                                String serviceId, int directionId, String monitoredStopCode,
                                UUID leadingObservationId, UUID followingObservationId, String status,
                                int expectedHeadwaySeconds, List<Integer> observedHeadwaySeconds,
                                Instant firstConfirmedAt, List<Instant> confirmingObservationTimes,
                                List<UUID> confirmingObservationIds,
                                List<String> evidenceIds, String detectionRuleVersion) {
    public BunchingEventFacts {
        observedHeadwaySeconds = List.copyOf(observedHeadwaySeconds);
        confirmingObservationTimes = List.copyOf(confirmingObservationTimes);
        confirmingObservationIds = List.copyOf(confirmingObservationIds);
        evidenceIds = List.copyOf(evidenceIds);
    }
}
