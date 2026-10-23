package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "poll_run", schema = "ingestion")
public class PollRun {
    protected PollRun() {}
    
    @Id
    private UUID id;
    private String source;
    private String scopeKey;
    private String idempotencyKey;
    private Instant startedAt;
    private Instant completedAt;
    private String outcome;
    private Integer httpStatus;
    private Integer recordsReceived;
    private String errorCode;
    private String errorMessage;
    private String classification = "OBSERVATION_ONLY";
    private UUID demoRunId;
    private UUID routePositionPollCycleId;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Integer getRecordsReceived() { return recordsReceived; }
    public void setRecordsReceived(Integer recordsReceived) { this.recordsReceived = recordsReceived; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public UUID getDemoRunId() { return demoRunId; }
    public void setDemoRunId(UUID demoRunId) { this.demoRunId = demoRunId; }
    public UUID getRoutePositionPollCycleId() { return routePositionPollCycleId; }
    public void setRoutePositionPollCycleId(UUID routePositionPollCycleId) { this.routePositionPollCycleId = routePositionPollCycleId; }

    @PrePersist
    void defaultToLiveClassification() {
        if (classification == null) classification = "OBSERVATION_ONLY";
    }
}
