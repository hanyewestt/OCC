package com.csd.occ.planning.diagnosis.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted, immutable producer-owned facts supplied before Planning accepts work. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProducerEventSubmission(String schemaVersion, EventType eventType, String producer, UUID eventId,
        UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
        List<String> evidenceIds, BunchingEventFacts eventSnapshot, TrafficIncidentEventFacts trafficSnapshot) {
    public ProducerEventSubmission {
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }

    public ProducerEventSubmission(String schemaVersion, EventType eventType, String producer, UUID eventId,
            UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
            List<String> evidenceIds, BunchingEventFacts eventSnapshot) {
        this(schemaVersion, eventType, producer, eventId, disruptionId, classification, demoRunId, detectedAt,
                detectionRuleVersion, evidenceIds, eventSnapshot, null);
    }
}
