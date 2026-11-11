package com.csd.occ.disruption.api.dto;

import com.csd.occ.disruption.domain.audit.ApplicationAuditEvent;
import com.csd.occ.disruption.domain.audit.AuditEvidenceSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Sanitized retained application audit event")
public record AdminAuditEventResponse(UUID id, Instant occurredAt, Instant recordedAt, String category, String eventType,
        String outcome, String sourceService, String actorSubject, String actorType, String entityType, String entityId,
        UUID runId, String busId, UUID captainId, String correlationId, String summary, boolean backfilled,
        Map<String, Object> details) {
    public AdminAuditEventResponse {
        summary = sanitizeSummary(summary);
        details = AuditEvidenceSanitizer.sanitize(details);
    }

    static String sanitizeSummary(String summary) {
        if (summary == null) return null;
        Object safe = AuditEvidenceSanitizer.sanitize(Map.of("summary", summary)).get("summary");
        return safe instanceof String text ? text : "[REDACTED]";
    }

    public static AdminAuditEventResponse detail(ApplicationAuditEvent event) {
        return new AdminAuditEventResponse(event.id(), event.occurredAt(), event.recordedAt(), event.category(),
                event.eventType(), event.outcome(), event.sourceService(), event.actorSubject(), event.actorType(),
                event.entityType(), event.entityId(), event.runId(), event.busId(), event.captainId(),
                event.correlationId(), event.summary(), event.backfilled(), event.details());
    }
}
