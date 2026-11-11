package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.AuditEvidenceSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Application audit event detail with sanitized evidence")
public record AuditEventDetailResponse(UUID id, Instant occurredAt, Instant recordedAt,
        String category, String eventType, String outcome, String sourceService, String actorSubject,
        String actorType, String entityType, String entityId, UUID runId, String busId, UUID captainId,
        String correlationId, String summary, boolean backfilled, Map<String, Object> details) {
    public AuditEventDetailResponse {
        details = AuditEvidenceSanitizer.sanitize(details);
    }
}
