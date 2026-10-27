package com.csd.occ.planning.diagnosis.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "planning_request", schema = "planning")
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
    private String failureCode;
    @jakarta.persistence.Column(name = "policy_outcome") private String policyOutcome;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;
    // Legacy requests predate input capture; new work captures input before acknowledgement.
    @jakarta.persistence.Column(columnDefinition = "text") private String inputSnapshot;
    public String inputSnapshot() { return inputSnapshot; }
    public void retainInput(String snapshot) { inputSnapshot = java.util.Objects.requireNonNull(snapshot); }

    public UUID id() { return id; }
    public String eventType() { return eventType; }
    public String producer() { return producer; }
    public UUID eventId() { return eventId; }
    public String schemaVersion() { return schemaVersion; }
    public DiagnosisRequestStatus status() { return DiagnosisRequestStatus.valueOf(status); }
    public int attemptCount() { return attemptCount; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Instant leaseUntil() { return leaseUntil; }
    public String failureCode() { return failureCode; }
    public String policyOutcome() { return policyOutcome; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void claim(Instant until, Instant now) {
        status = DiagnosisRequestStatus.PROCESSING.name();
        leaseUntil = until;
        attemptCount++;
        updatedAt = now;
    }

    public void complete(Instant now) {
        status = DiagnosisRequestStatus.COMPLETED.name();
        leaseUntil = null;
        failureCode = null;
        updatedAt = now;
    }

    public void completeNoAction(String outcome, Instant now) {
        if (status().equals(DiagnosisRequestStatus.COMPLETED) && outcome.equals(policyOutcome)) return;
        status = DiagnosisRequestStatus.COMPLETED.name();
        leaseUntil = null;
        failureCode = null;
        policyOutcome = java.util.Objects.requireNonNull(outcome);
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
