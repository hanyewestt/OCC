package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.ScopedHazardRequestPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningHazardRequestPageResponse(String mode, UUID demoRunId, List<HazardRequestItem> items,
        int page, int size, long totalItems, int totalPages) {
    public record HazardRequestItem(UUID requestId, UUID eventId, UUID disruptionId, String eventType, String status,
            String failureCode, Instant createdAt) {}

    public static PlanningHazardRequestPageResponse from(ScopedHazardRequestPage page) {
        return new PlanningHazardRequestPageResponse(page.mode(), page.demoRunId(), page.items().stream().map(item ->
                new HazardRequestItem(item.requestId(), item.eventId(), item.disruptionId(), item.eventType(),
                        item.status().name(), item.failureCode(), item.createdAt())).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}
