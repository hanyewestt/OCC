package com.csd.occ.planning.advisory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HazardEventFacts(UUID eventId, UUID disruptionId, String action, String inactiveReason,
        String classification, UUID demoRunId, UUID targetRunId, UUID sourceObservationId, Long sourceRevision, UUID pollRunId,
        String targetBusId, Instant eventAt, Fact facts, ResolutionEvidence resolutionEvidence) {
    public HazardEventFacts(UUID eventId, UUID disruptionId, String action, String inactiveReason,
            String classification, UUID demoRunId, UUID sourceObservationId, Long sourceRevision, UUID pollRunId,
            String targetBusId, Instant eventAt, Fact facts, ResolutionEvidence resolutionEvidence) {
        this(eventId, disruptionId, action, inactiveReason, classification, demoRunId, null, sourceObservationId,
                sourceRevision, pollRunId, targetBusId, eventAt, facts, resolutionEvidence);
    }

    public HazardEventFacts(UUID eventId, UUID disruptionId, String action, String inactiveReason,
            String classification, UUID demoRunId, UUID sourceObservationId, UUID pollRunId, Instant eventAt, Fact facts) {
        this(eventId, disruptionId, action, inactiveReason, classification, demoRunId, null, sourceObservationId,
                null, pollRunId, null, eventAt, facts, null);
    }

    public record ResolutionEvidence(String method, UUID sourceObservationId, UUID pollRunId,
            Instant fetchedAt, Fact facts) {}

    public record Fact(String kind, String sourceEventKey, String sourceAction, Instant reportedAt,
                Instant effectiveFrom, Instant effectiveUntil, String title, String description,
                String severity, String instruction, List<String> references, String line, String direction,
                String serviceStatus, String department, List<Location> locations) {}
    public record Location(String type, String sourceReference, String description, BigDecimal latitude,
                    BigDecimal longitude, BigDecimal radiusKm, String geometryRole, String provenance) {}
}
