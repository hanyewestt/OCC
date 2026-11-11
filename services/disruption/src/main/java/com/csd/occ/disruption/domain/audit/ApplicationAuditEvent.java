package com.csd.occ.disruption.domain.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "application_audit_event", schema = "disruption")
public class ApplicationAuditEvent {
    @Id private UUID id;
    private int eventSchemaVersion = 1;
    private Instant occurredAt;
    private Instant recordedAt;
    private String category;
    private String eventType;
    private String outcome;
    private String sourceService = "DISRUPTION";
    private String actorSubject;
    private String actorType;
    private String entityType;
    private String entityId;
    private UUID runId;
    private String busId;
    private UUID captainId;
    private String correlationId;
    private String summary;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> details = Map.of();
    private String sourceKey;
    private boolean backfilled;
    protected ApplicationAuditEvent() { }

    public ApplicationAuditEvent(Instant occurredAt, Instant recordedAt, String category, String eventType,
            String outcome, String actorSubject, String actorType, String entityType, String entityId, UUID runId, String summary,
            Map<String, Object> details, String sourceKey) {
        this(occurredAt, recordedAt, category, eventType, outcome, actorSubject, actorType, entityType, entityId,
                runId, summary, details, sourceKey, false);
    }

    public ApplicationAuditEvent(Instant occurredAt, Instant recordedAt, String category, String eventType,
            String outcome, String actorSubject, String actorType, String entityType, String entityId, UUID runId, String summary,
            Map<String, Object> details, String sourceKey, boolean backfilled) {
        this.id = UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.category = category;
        this.eventType = eventType;
        this.outcome = outcome;
        this.actorSubject = actorSubject;
        this.actorType = actorType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.runId = runId;
        this.summary = summary;
        this.details = AuditEvidenceSanitizer.sanitize(details);
        this.sourceKey = sourceKey;
        this.backfilled = backfilled;
    }

    public UUID id() { return id; }
    public Instant occurredAt() { return occurredAt; }
    public Instant recordedAt() { return recordedAt; }
    public String category() { return category; }
    public String eventType() { return eventType; }
    public String outcome() { return outcome; }
    public String sourceService() { return sourceService; }
    public String actorType() { return actorType; }
    public String entityType() { return entityType; }
    public String entityId() { return entityId; }
    public UUID runId() { return runId; }
    public String summary() { return summary; }
    public Map<String, Object> details() { return details; }
    public String sourceKey() { return sourceKey; }
    public boolean backfilled() { return backfilled; }
}
