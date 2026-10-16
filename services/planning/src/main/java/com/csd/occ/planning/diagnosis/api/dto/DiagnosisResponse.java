package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DiagnosisResponse(UUID diagnosisId, DiagnosisRequestStatus status, String failureCode,
                                String causeCode, String causeSubtype, String likelyCause, Double confidence,
                                Double evidenceSupportScore, List<String> evidenceIds, List<String> limitations,
                                Instant completedAt, String eventType, UUID eventId, String producer,
                                @io.swagger.v3.oas.annotations.media.Schema(description = "Current delivery status; null once completed operational delivery state expires")
                                String recommendationDeliveryStatus,
                                String recommendationDeliveryFailureCode) {
    public DiagnosisResponse { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations); }
}
