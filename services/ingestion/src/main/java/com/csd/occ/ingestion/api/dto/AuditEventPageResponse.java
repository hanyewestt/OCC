package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalItems", "totalPages", "retentionSeconds", "collectionStartedAt"})
public record AuditEventPageResponse(List<AuditEventSummaryResponse> items, int page, int size, long totalItems,
        int totalPages, long retentionSeconds, Instant collectionStartedAt) {
    public AuditEventPageResponse { items = List.copyOf(items); }
}
