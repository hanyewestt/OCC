package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningDiagnosisSummary(UUID requestId, UUID eventId, UUID diagnosisId,
        String classification, UUID demoRunId, String serviceId, Integer directionId, String stopCode,
        List<String> affectedBusReferences, DiagnosisRequestStatus status, boolean processingRequested,
        String likelyCause, Double confidence, List<String> limitations,
        List<DiagnosisEvidenceDetail> evidenceDetails,
        String failureCode, String policyOutcome, Instant createdAt) {
    public PlanningDiagnosisSummary {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        affectedBusReferences = affectedBusReferences == null ? List.of() : List.copyOf(affectedBusReferences);
        evidenceDetails = evidenceDetails == null ? List.of() : List.copyOf(evidenceDetails);
    }
}
