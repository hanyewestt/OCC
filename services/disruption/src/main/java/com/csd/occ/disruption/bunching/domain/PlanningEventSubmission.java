package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned service-to-service envelope stored durably before Planning is called. */
public record PlanningEventSubmission(String schemaVersion, String eventType, String producer, UUID eventId,
        UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
        List<String> evidenceIds, BunchingEventSnapshot eventSnapshot) {
    public static final String SCHEMA_VERSION = "planning-event-v1";

    public PlanningEventSubmission {
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }
}
