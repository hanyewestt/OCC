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
    private Map<String,Object> details;
    private String sourceKey;
    private boolean backfilled;
    protected ApplicationAuditEvent() { }
    public ApplicationAuditEvent(UUID id,Instant occurredAt,Instant recordedAt,String category,String eventType,String outcome,
            String sourceService,String actorSubject,String actorType,String entityType,String entityId,UUID runId,String busId,
            UUID captainId,String correlationId,String summary,Map<String,?> details,String sourceKey) {
        this.id=id; eventSchemaVersion=1; this.occurredAt=occurredAt; this.recordedAt=recordedAt; this.category=category;
        this.eventType=eventType; this.outcome=outcome; this.sourceService=sourceService; this.actorSubject=actorSubject;
        this.actorType=actorType; this.entityType=entityType; this.entityId=entityId; this.runId=runId; this.busId=busId;
        this.captainId=captainId; this.correlationId=correlationId; this.summary=summary; this.details=AuditEvidenceSanitizer.sanitize(details); this.sourceKey=sourceKey;
    }
    public UUID id() { return id; }
    public Instant occurredAt() { return occurredAt; }
    public Instant recordedAt() { return recordedAt; }
    public String category() { return category; }
    public String eventType() { return eventType; }
    public String outcome() { return outcome; }
    public String sourceService() { return sourceService; }
    public String actorSubject() { return actorSubject; }
    public String actorType() { return actorType; }
    public String entityType() { return entityType; }
    public String entityId() { return entityId; }
    public UUID runId() { return runId; }
    public String busId() { return busId; }
    public UUID captainId() { return captainId; }
    public String correlationId() { return correlationId; }
    public String summary() { return summary; }
    public Map<String,Object> details() { return details; }
    public String sourceKey() { return sourceKey; }
    public boolean backfilled() { return backfilled; }
}
