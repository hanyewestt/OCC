package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.ScopedDiagnosisPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningDiagnosisPageResponse(String mode, UUID demoRunId, List<DiagnosisItem> items,
        int page, int size, long totalItems, int totalPages) {
    public record DiagnosisItem(UUID requestId, UUID eventId, UUID diagnosisId, String classification, UUID demoRunId,
            String serviceId, int directionId, String stopCode, String status, String likelyCause, Double confidence,
            List<String> limitations, String failureCode, String policyOutcome, Instant createdAt) { }

    public static PlanningDiagnosisPageResponse from(ScopedDiagnosisPage page) {
        return new PlanningDiagnosisPageResponse(page.mode(), page.demoRunId(), page.items().stream().map(item ->
                new DiagnosisItem(item.requestId(), item.eventId(), item.diagnosisId(), item.classification(), item.demoRunId(),
                        item.serviceId(), item.directionId(), item.stopCode(), item.status().name(), item.likelyCause(),
                        item.confidence(), item.limitations(), item.failureCode(), item.policyOutcome(), item.createdAt())).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}
