package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.ScopedHazardHistoryPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Latest terminal hazard processing per disruption in the selected live or simulation scope")
public record PlanningHazardHistoryPageResponse(String mode, UUID demoRunId, List<HazardHistoryItem> items,
        int page, int size, long totalItems, int totalPages) {
    public record HazardHistoryItem(UUID requestId, UUID eventId, UUID disruptionId, String eventType, String title,
            @Schema(allowableValues = {"COMPLETED", "FAILED_TERMINAL"}) String status,
            String failureCode, Instant createdAt,
            @Schema(description = "UTC time processing completed successfully or failed terminally") Instant completedAt) {}

    public static PlanningHazardHistoryPageResponse from(ScopedHazardHistoryPage page) {
        return new PlanningHazardHistoryPageResponse(page.mode(), page.demoRunId(), page.items().stream().map(item ->
                new HazardHistoryItem(item.requestId(), item.eventId(), item.disruptionId(), item.eventType(), item.title(),
                        item.status().name(), item.failureCode(), item.createdAt(), item.completedAt())).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}
