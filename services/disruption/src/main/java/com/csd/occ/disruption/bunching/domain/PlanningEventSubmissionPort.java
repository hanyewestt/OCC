package com.csd.occ.disruption.bunching.domain;

/** Encodes and verifies immutable Planning event submissions stored with the Disruption outbox. */
public interface PlanningEventSubmissionPort {
    EncodedPlanningEventSubmission encode(AlertEvent event);

    /** Computes the canonical semantic fingerprint and verifies the persisted payload's identity. */
    String fingerprint(String payloadJson, java.util.UUID expectedEventId);
}
