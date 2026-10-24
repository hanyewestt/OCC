package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningDiagnosisSummary(UUID requestId, UUID eventId, UUID diagnosisId,
        String classification, UUID demoRunId, String serviceId, int directionId, String stopCode,
        DiagnosisRequestStatus status, String likelyCause, Double confidence, List<String> limitations,
        String failureCode, String policyOutcome, Instant createdAt) {
    public PlanningDiagnosisSummary { limitations = List.copyOf(limitations); }
}
