package com.csd.occ.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable idempotency receipt independent of operational demo run retention. */
@Entity
@Table(name = "demo_assignment_receipt", schema = "ingestion")
public class DemoAssignmentReceipt {
    protected DemoAssignmentReceipt() { }

    public DemoAssignmentReceipt(UUID operationId, UUID runId, String busId, UUID captainUserId,
            long expectedVersion, String actorSubject, Instant createdAt) {
        this.operationId = Objects.requireNonNull(operationId);
        demoRunId = Objects.requireNonNull(runId);
        this.busId = Objects.requireNonNull(busId);
        this.captainUserId = captainUserId;
        this.expectedVersion = expectedVersion;
        this.actorSubject = Objects.requireNonNull(actorSubject);
        outcome = "PENDING";
        httpStatus = 202;
        beforeVersion = expectedVersion;
        afterVersion = expectedVersion;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;
    @Column(name = "demo_run_id", nullable = false)
    private UUID demoRunId;
    @Column(name = "bus_id", nullable = false)
    private String busId;
    @Column(name = "captain_user_id")
    private UUID captainUserId;
    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;
    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;
    @Column(name = "outcome", nullable = false)
    private String outcome;
    @Column(name = "http_status", nullable = false)
    private short httpStatus;
    @Column(name = "previous_captain_user_id")
    private UUID previousCaptainUserId;
    @Column(name = "before_version", nullable = false)
    private long beforeVersion;
    @Column(name = "after_version", nullable = false)
    private long afterVersion;
    @Column(name = "reason")
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public boolean matches(UUID runId, String bus, UUID captain, long version, String actor) {
        return demoRunId.equals(runId) && busId.equals(bus) && Objects.equals(captainUserId, captain)
                && expectedVersion == version && actorSubject.equals(actor);
    }

    public boolean completed() { return !"PENDING".equals(outcome); }
    public int httpStatus() { return httpStatus; }
    public String outcome() { return outcome; }
    public UUID operationId() { return operationId; }
    public UUID demoRunId() { return demoRunId; }
    public String busId() { return busId; }
    public String actorSubject() { return actorSubject; }
    public UUID captainUserId() { return captainUserId; }
    public UUID previousCaptainUserId() { return previousCaptainUserId; }
    public long beforeVersion() { return beforeVersion; }
    public long afterVersion() { return afterVersion; }
    public String reason() { return reason; }

    public void finish(String result, int status, UUID previousCaptain, long before, long after, String safeReason) {
        outcome = Objects.requireNonNull(result);
        httpStatus = (short) status;
        previousCaptainUserId = previousCaptain;
        beforeVersion = before;
        afterVersion = after;
        reason = safeReason;
    }
}
