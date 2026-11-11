package com.csd.occ.disruption.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "A page of retained application audit events")
public record AdminAuditEventPageResponse(List<AdminAuditEventSummaryResponse> items, int page, int size,
        long totalItems, int totalPages, long retentionSeconds, Instant collectionStartedAt) {
    public AdminAuditEventPageResponse { items = List.copyOf(items); }
}
