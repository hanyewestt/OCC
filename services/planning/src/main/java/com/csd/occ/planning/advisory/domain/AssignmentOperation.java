package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bus_assignment_operation", schema = "planning")
public class AssignmentOperation {
    @Id private UUID id;
    private String actorSubject;
    private String idempotencyKey;
    private UUID demoRunId;
    private String busId;
    private UUID captainUserId;
    private long expectedVersion;
    private String status;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> result = Map.of();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastDispatchAt;
    protected AssignmentOperation() { }
    public AssignmentOperation(UUID id, String actorSubject, String idempotencyKey, UUID demoRunId, String busId,
            UUID captainUserId, long expectedVersion, String status, Map<String, Object> result, Instant now) {
        this.id=id; this.actorSubject=actorSubject; this.idempotencyKey=idempotencyKey; this.demoRunId=demoRunId;
        this.busId=busId; this.captainUserId=captainUserId; this.expectedVersion=expectedVersion; this.status=status;
        this.result=immutableResult(result); this.createdAt=now; this.updatedAt=now;
    }
    public UUID id() { return id; }
    public String actorSubject() { return actorSubject; }
    public String idempotencyKey() { return idempotencyKey; }
    public UUID demoRunId() { return demoRunId; }
    public String busId() { return busId; }
    public UUID captainUserId() { return captainUserId; }
    public long expectedVersion() { return expectedVersion; }
    public String status() { return status; }
    public Map<String, Object> result() { return result; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant lastDispatchAt() { return lastDispatchAt; }
    public void markDispatchAttempt(Instant now) { this.lastDispatchAt = now; }
    public boolean matches(UUID run, String bus, UUID captain, long version) {
        return demoRunId.equals(run) && busId.equals(bus) && java.util.Objects.equals(captainUserId, captain)
                && expectedVersion == version;
    }
    public void finish(String status, Map<String, Object> result, Instant now) {
        this.status=status; this.result=immutableResult(result); this.updatedAt=now;
    }
    private static Map<String, Object> immutableResult(Map<String, Object> result) {
        return result == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(result));
    }
    @Override public String toString() { return "AssignmentOperation[id=" + id + ", actor=" + actorSubject + ", status=" + status + "]"; }
}
