package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.ScopedDiagnosisPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningDiagnosisPageResponse(String mode, UUID demoRunId, List<DiagnosisItem> items,
        int page, int size, long totalItems, int totalPages) {
    public record DiagnosisItem(UUID requestId, UUID eventId, UUID diagnosisId, String classification, UUID demoRunId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String serviceId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Integer directionId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String stopCode,
            String status, String likelyCause, Double confidence,
            List<String> limitations,
            @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems = 50,
                    arraySchema = @io.swagger.v3.oas.annotations.media.Schema(
                            description = "Bounded source facts; IDs are technical provenance"))
            List<DiagnosisEvidenceDetailResponse> evidenceDetails,
            String failureCode, String policyOutcome, Instant createdAt) { }

    public static PlanningDiagnosisPageResponse from(ScopedDiagnosisPage page) {
        return new PlanningDiagnosisPageResponse(page.mode(), page.demoRunId(), page.items().stream().map(item ->
                new DiagnosisItem(item.requestId(), item.eventId(), item.diagnosisId(), item.classification(), item.demoRunId(),
                        item.serviceId(), item.directionId(), item.stopCode(), item.status().name(), item.likelyCause(),
                        item.confidence(), item.limitations(), item.evidenceDetails().stream()
                                .map(DiagnosisEvidenceDetailResponse::from).toList(),
                        item.failureCode(), item.policyOutcome(), item.createdAt())).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}
