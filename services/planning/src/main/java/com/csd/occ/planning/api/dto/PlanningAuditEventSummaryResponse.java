package com.csd.occ.planning.api.dto;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import java.time.Instant;
import java.util.UUID;

public record PlanningAuditEventSummaryResponse(UUID id, int eventSchemaVersion, Instant occurredAt, Instant recordedAt,
        String category, String eventType, String outcome, String sourceService, String actorSubject, String actorType,
        String entityType, String entityId, UUID runId, String busId, UUID captainId, String correlationId,
        String summary, boolean backfilled) {
    public static PlanningAuditEventSummaryResponse from(ApplicationAuditEvent event) {
        return new PlanningAuditEventSummaryResponse(event.id(), event.eventSchemaVersion(), event.occurredAt(),
                event.recordedAt(), event.category(), event.eventType(), event.outcome(), event.sourceService(),
                event.actorSubject(), event.actorType(), event.entityType(), event.entityId(), event.runId(),
                event.busId(), event.captainId(), event.correlationId(), event.summary(), event.backfilled());
    }
}
