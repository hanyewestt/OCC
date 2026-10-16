package com.csd.occ.disruption.bunching.domain;

public record EncodedPlanningEventSubmission(String payloadJson, String payloadFingerprint, String schemaVersion) { }
