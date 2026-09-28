package com.csd.occ.diagnosis.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recommendation_delivery", schema = "diagnosis")
public class RecommendationDelivery {
    protected RecommendationDelivery() { }

    public RecommendationDelivery(UUID id, UUID diagnosisId, String payloadJson, String fingerprint, Instant now) {
        this.id = id; this.diagnosisId = diagnosisId; this.payloadJson = payloadJson;
        payloadFingerprint = fingerprint; status = "PENDING"; nextAttemptAt = now; createdAt = now;
    }

    @Id private UUID id;
    private UUID diagnosisId;
    private String payloadJson;
    private String payloadFingerprint;
    private String status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private Integer lastHttpStatus;
    private String failureCode;
    private String responseJson;
    private Instant createdAt;
    private Instant deliveredAt;

    public UUID id() { return id; }
    public UUID diagnosisId() { return diagnosisId; }
    public String payloadJson() { return payloadJson; }
    public String status() { return status; }
    public String failureCode() { return failureCode; }
    public int attemptCount() { return attemptCount; }
    public Instant nextAttemptAt() { return nextAttemptAt; }

    public void claimed(Instant leaseUntil) { attemptCount++; this.leaseUntil = leaseUntil; status = "PROCESSING"; }
    public void delivered(int status, String response, Instant now) {
        lastHttpStatus = status; responseJson = response; deliveredAt = now;
        this.status = "DELIVERED"; failureCode = null; leaseUntil = null;
    }
    public void retry(Integer status, String code, Instant next) {
        lastHttpStatus = status; failureCode = code; nextAttemptAt = next; this.status = "PENDING"; leaseUntil = null;
    }
    public void terminal(Integer status, String code) {
        lastHttpStatus = status; failureCode = code; this.status = "FAILED_TERMINAL"; leaseUntil = null;
    }
}
