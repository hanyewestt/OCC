package com.csd.occ.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DiagnosisView(UUID diagnosisId, DiagnosisRequestStatus status, String failureCode,
                            String causeCode, String causeSubtype, String likelyCause, Double confidence,
                            Double evidenceSupportScore, List<String> evidenceIds, List<String> limitations,
                            Instant completedAt, String eventType, UUID eventId, String producer,
                            String recommendationDeliveryStatus,
                            String recommendationDeliveryFailureCode) {
    public DiagnosisView {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
