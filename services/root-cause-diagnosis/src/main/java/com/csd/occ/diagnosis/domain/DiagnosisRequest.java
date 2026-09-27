package com.csd.occ.diagnosis.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "diagnosis_request", schema = "diagnosis")
public class DiagnosisRequest {
    protected DiagnosisRequest() { }

    public DiagnosisRequest(UUID id, EventType eventType, String producer, UUID eventId, String schemaVersion,
                            Instant now) {
        this.id = id;
        this.eventType = eventType.name();
        this.producer = producer;
        this.eventId = eventId;
        this.schemaVersion = schemaVersion;
        this.status = DiagnosisRequestStatus.PENDING.name();
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Id private UUID id;
    private String eventType;
    private String producer;
    private UUID eventId;
    private String schemaVersion;
    private String status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private UUID diagnosisId;
    private String failureCode;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    public UUID id() { return id; }
    public String eventType() { return eventType; }
    public String producer() { return producer; }
    public UUID eventId() { return eventId; }
    public String schemaVersion() { return schemaVersion; }
    public DiagnosisRequestStatus status() { return DiagnosisRequestStatus.valueOf(status); }
    public int attemptCount() { return attemptCount; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Instant leaseUntil() { return leaseUntil; }
    public UUID diagnosisId() { return diagnosisId; }
    public String failureCode() { return failureCode; }
    public Instant createdAt() { return createdAt; }

    public void claim(Instant until, Instant now) {
        status = DiagnosisRequestStatus.PROCESSING.name();
        leaseUntil = until;
        attemptCount++;
        updatedAt = now;
    }

    public void complete(UUID diagnosisId, Instant now) {
        this.diagnosisId = diagnosisId;
        status = DiagnosisRequestStatus.COMPLETED.name();
        leaseUntil = null;
        failureCode = null;
        updatedAt = now;
    }

    public void failRetryable(String code, Instant retryAt, Instant now) {
        status = DiagnosisRequestStatus.FAILED_RETRYABLE.name();
        failureCode = code;
        nextAttemptAt = retryAt;
        leaseUntil = null;
        updatedAt = now;
    }

    public void failTerminal(String code, Instant now) {
        status = DiagnosisRequestStatus.FAILED_TERMINAL.name();
        failureCode = code;
        leaseUntil = null;
        updatedAt = now;
    }
}
