package com.csd.occ.planning.api.dto;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.AuditEvidenceSanitizer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PlanningAuditEventDetailResponse(UUID id, int eventSchemaVersion, Instant occurredAt, Instant recordedAt,
        String category, String eventType, String outcome, String sourceService, String actorSubject, String actorType,
        String entityType, String entityId, UUID runId, String busId, UUID captainId, String correlationId,
        String summary, boolean backfilled, Map<String, Object> details) {
    public static PlanningAuditEventDetailResponse from(ApplicationAuditEvent event) {
        return new PlanningAuditEventDetailResponse(event.id(), event.eventSchemaVersion(), event.occurredAt(),
                event.recordedAt(), event.category(), event.eventType(), event.outcome(), event.sourceService(),
                event.actorSubject(), event.actorType(), event.entityType(), event.entityId(), event.runId(),
                event.busId(), event.captainId(), event.correlationId(),
                (String) AuditEvidenceSanitizer.sanitize(java.util.Collections.singletonMap("summary", event.summary())).get("summary"), event.backfilled(),
                AuditEvidenceSanitizer.sanitize(event.details()));
    }
}
