package com.csd.occ.disruption.bunching.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentPlanningSnapshot;

/** Versioned service-to-service envelope stored durably before Planning is called. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanningEventSubmission(String schemaVersion, String eventType, String producer, UUID eventId,
        UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
        List<String> evidenceIds, BunchingEventSnapshot eventSnapshot,
        TrafficIncidentPlanningSnapshot trafficSnapshot) {
    public static final String SCHEMA_VERSION = "planning-event-v1";

    public PlanningEventSubmission {
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }

    public PlanningEventSubmission(String schemaVersion, String eventType, String producer, UUID eventId,
            UUID disruptionId, String classification, UUID demoRunId, Instant detectedAt, String detectionRuleVersion,
            List<String> evidenceIds, BunchingEventSnapshot eventSnapshot) {
        this(schemaVersion, eventType, producer, eventId, disruptionId, classification, demoRunId, detectedAt,
                detectionRuleVersion, evidenceIds, eventSnapshot, null);
    }
}
