package com.csd.occ.planning.diagnosis.domain;

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
        bounded(classification, 30, "classification");
        bounded(serviceId, 20, "serviceId");
        bounded(monitoredStopCode, 12, "monitoredStopCode");
        bounded(status, 30, "status");
        bounded(detectionRuleVersion, 80, "detectionRuleVersion");
        observedHeadwaySeconds = EvidenceBounds.copy(observedHeadwaySeconds, 10, "observedHeadwaySeconds");
        if (observedHeadwaySeconds.size() < 2 || observedHeadwaySeconds.size() > 10)
            throw new EvidenceLimitExceededException("observedHeadwaySeconds");
        confirmingObservationTimes = EvidenceBounds.copy(confirmingObservationTimes, 20, "confirmingObservationTimes");
        confirmingObservationIds = EvidenceBounds.copy(confirmingObservationIds, 20, "confirmingObservationIds");
        evidenceIds = EvidenceBounds.copyStrings(evidenceIds, 100, 200, "evidenceIds");
    }

    private static void bounded(String value, int max, String field) {
        if (value != null && value.length() > max) throw new EvidenceLimitExceededException(field);
    }

}
