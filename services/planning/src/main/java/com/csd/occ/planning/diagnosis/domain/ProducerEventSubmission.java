package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted, immutable producer-owned facts supplied before Planning accepts work. */
public record ProducerEventSubmission(String schemaVersion, EventType eventType, String producer, UUID eventId,
        UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
        List<String> evidenceIds, BunchingEventFacts eventSnapshot) {
    public ProducerEventSubmission {
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }
}
