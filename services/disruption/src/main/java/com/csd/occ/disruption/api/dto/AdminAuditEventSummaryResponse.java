package com.csd.occ.disruption.api.dto;

import com.csd.occ.disruption.domain.audit.ApplicationAuditEvent;
import com.csd.occ.disruption.domain.audit.AuditEvidenceSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Application audit event without evidence details")
public record AdminAuditEventSummaryResponse(UUID id, Instant occurredAt, Instant recordedAt, String category,
        String eventType, String outcome, String sourceService, String actorSubject, String actorType, String entityType,
        String entityId, UUID runId, String busId, UUID captainId, String correlationId, String summary,
        boolean backfilled) {
    public AdminAuditEventSummaryResponse {
        if (summary != null) {
            Object safe = AuditEvidenceSanitizer.sanitize(java.util.Map.of("summary", summary)).get("summary");
            summary = safe instanceof String text ? text : "[REDACTED]";
        }
    }

    public static AdminAuditEventSummaryResponse from(ApplicationAuditEvent event) {
        return new AdminAuditEventSummaryResponse(event.id(), event.occurredAt(), event.recordedAt(), event.category(),
                event.eventType(), event.outcome(), event.sourceService(), event.actorSubject(), event.actorType(),
                event.entityType(), event.entityId(), event.runId(), event.busId(), event.captainId(),
                event.correlationId(), event.summary(), event.backfilled());
    }
}
