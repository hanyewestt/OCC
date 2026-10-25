package com.csd.occ.disruption.bunching.domain;

/** Encodes and verifies immutable Planning event submissions stored with the Disruption outbox. */
public interface PlanningEventSubmissionPort {
    EncodedPlanningEventSubmission encodeHazard(com.csd.occ.disruption.hazard.domain.HazardEventSnapshot snapshot);
    EncodedPlanningEventSubmission encode(AlertEvent event);

    EncodedPlanningEventSubmission encodeTraffic(
            com.csd.occ.disruption.traffic.domain.TrafficIncidentPlanningSnapshot snapshot);

    /** Computes the canonical semantic fingerprint and verifies the persisted payload's identity. */
    String fingerprint(String payloadJson, java.util.UUID expectedEventId);
}
