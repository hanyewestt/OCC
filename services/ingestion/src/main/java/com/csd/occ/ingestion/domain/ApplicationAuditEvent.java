package com.csd.occ.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="application_audit_event",schema="ingestion")
public class ApplicationAuditEvent {
    @Id private UUID id;
    private int eventSchemaVersion;
    private Instant occurredAt;
    private Instant recordedAt;
    private String category;
    private String eventType;
    private String outcome;
    private String sourceService;
    private String actorSubject;
    private String actorType;
    private String entityType;
    private String entityId;
    // Optional event-time context; deliberately no cascading operational foreign keys.
    private UUID runId;
    private String busId;
    private UUID captainId;
    private String correlationId;
    private String summary;
    // Hibernate's JSON mapping is necessary for PostgreSQL JSONB, as in existing domain evidence entities.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Map<String,String> details;
    private String sourceKey;
    protected ApplicationAuditEvent() { }
    public ApplicationAuditEvent(UUID id,Instant occurredAt,Instant recordedAt,String category,String eventType,String outcome,
            String sourceService,String actorSubject,String actorType,String entityType,String entityId,UUID runId,String busId,
            UUID captainId,String correlationId,String summary,Map<String,String> details,String sourceKey) {
        this.id=id; eventSchemaVersion=1; this.occurredAt=occurredAt; this.recordedAt=recordedAt; this.category=category;
        this.eventType=eventType; this.outcome=outcome; this.sourceService=sourceService; this.actorSubject=actorSubject;
        this.actorType=actorType; this.entityType=entityType; this.entityId=entityId; this.runId=runId; this.busId=busId;
        this.captainId=captainId; this.correlationId=correlationId; this.summary=summary; this.details=Map.copyOf(details); this.sourceKey=sourceKey;
    }
}
