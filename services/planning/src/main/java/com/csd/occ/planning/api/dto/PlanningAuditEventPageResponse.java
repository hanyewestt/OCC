package com.csd.occ.planning.api.dto;

import java.time.Instant;
import java.util.List;

public record PlanningAuditEventPageResponse(List<PlanningAuditEventSummaryResponse> items, int page, int size,
        long totalItems, int totalPages, long retentionSeconds, Instant collectionStartedAt) {
    public PlanningAuditEventPageResponse { items = List.copyOf(items); }
}
