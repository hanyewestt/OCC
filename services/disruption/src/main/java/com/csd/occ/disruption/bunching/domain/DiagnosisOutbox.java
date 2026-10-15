package com.csd.occ.disruption.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "planning_outbox", schema = "disruption")
public class DiagnosisOutbox {
    protected DiagnosisOutbox() { }
    public DiagnosisOutbox(UUID id, UUID eventId, Instant now) {
        this(id, eventId, null, null, null, now);
    }
    public DiagnosisOutbox(UUID id, UUID eventId, String payloadJson, String payloadFingerprint,
            String schemaVersion, Instant now) {
        this.id = id; this.eventId = eventId; status = "PENDING"; attemptCount = 0;
        nextAttemptAt = now; createdAt = now;
        this.payloadJson = payloadJson;
        this.payloadFingerprint = payloadFingerprint;
        this.schemaVersion = schemaVersion;
    }
    @Id private UUID id;
    private UUID eventId;
    private String status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private Integer lastHttpStatus;
    private String failureCode;
    private Instant createdAt;
    private Instant deliveredAt;
    @Column(name = "payload_json", columnDefinition = "text") private String payloadJson;
    @Column(name = "payload_fingerprint") private String payloadFingerprint;
    @Column(name = "schema_version") private String schemaVersion;
    public UUID id() { return id; }
    public UUID eventId() { return eventId; }
    public int attemptCount() { return attemptCount; }
    public String payloadJson() { return payloadJson; }
    public String payloadFingerprint() { return payloadFingerprint; }
    public String schemaVersion() { return schemaVersion; }
    public void retainFingerprint(String fingerprint) {
        if (payloadFingerprint == null) payloadFingerprint = java.util.Objects.requireNonNull(fingerprint);
    }
    public void claimed(Instant until) { attemptCount++; leaseUntil = until; status = "PROCESSING"; }
    public void delivered(Integer status, Instant now) { this.status = "DELIVERED"; lastHttpStatus = status; deliveredAt = now; failureCode = null; leaseUntil = null; }
    public void retry(Integer httpStatus, String code, Instant retryAt) { status = "PENDING"; lastHttpStatus = httpStatus; failureCode = code; nextAttemptAt = retryAt; leaseUntil = null; }
    public void fail(Integer status, String code) { this.status = "FAILED_TERMINAL"; lastHttpStatus = status; failureCode = code; leaseUntil = null; }
}
