package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Safe application audit summary without evidence details")
public record AuditEventSummaryResponse(UUID id, Instant occurredAt, Instant recordedAt,
        String category, String eventType, String outcome, String sourceService, String actorSubject,
        String actorType, String entityType, String entityId, UUID runId, String busId, UUID captainId,
        String correlationId, String summary, boolean backfilled) { }
