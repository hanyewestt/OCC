package com.csd.occ.disruption.hazard.domain;

import java.time.Instant;
import java.util.UUID;

public record HazardEventSnapshot(UUID eventId, UUID disruptionId, String action, String inactiveReason,
        String classification, UUID demoRunId, UUID targetRunId, UUID sourceObservationId, Long sourceRevision, UUID pollRunId,
        String targetBusId, Instant eventAt, HazardPollPort.Fact facts, ResolutionEvidence resolutionEvidence) {
    public HazardEventSnapshot(UUID eventId, UUID disruptionId, String action, String inactiveReason,
            String classification, UUID demoRunId, UUID sourceObservationId, UUID pollRunId,
            Instant eventAt, HazardPollPort.Fact facts) {
        this(eventId, disruptionId, action, inactiveReason, classification, demoRunId, null, sourceObservationId,
                null, pollRunId, null, eventAt, facts, null);
    }

    public HazardEventSnapshot(UUID eventId, UUID disruptionId, String action, String inactiveReason,
            String classification, UUID demoRunId, UUID sourceObservationId, Long sourceRevision, UUID pollRunId,
            String targetBusId, Instant eventAt, HazardPollPort.Fact facts, ResolutionEvidence resolutionEvidence) {
        this(eventId, disruptionId, action, inactiveReason, classification, demoRunId, null, sourceObservationId,
                sourceRevision, pollRunId, targetBusId, eventAt, facts, resolutionEvidence);
    }

    public record ResolutionEvidence(String method, UUID sourceObservationId, UUID pollRunId,
            Instant fetchedAt, HazardPollPort.Fact facts) {}
}
