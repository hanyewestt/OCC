package com.csd.occ.disruption.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_processing_state", schema = "disruption")
public class SourceProcessingState {
    protected SourceProcessingState() { }
    public SourceProcessingState(UUID rawObservationId, Instant nextAttemptAt, UUID demoRunId) {
        this.rawObservationId = rawObservationId; status = "DEFERRED"; this.nextAttemptAt = nextAttemptAt; this.demoRunId = demoRunId;
    }
    @Id private UUID rawObservationId;
    @jakarta.persistence.Version private long version;
    private String status;
    private Instant nextAttemptAt;
    private Instant processedAt;
    private Instant rejectedAt;
    private String reason;
    private UUID demoRunId;
    private Instant evaluatedAt;
    private BigDecimal thresholdSeconds;
    private Integer bunchedGroupCount;
    // Legacy markers without a known sequence are retained conservatively.
    private Long sourceSequence;
    public Long sourceSequence() { return sourceSequence; }
    public void recordSequence(long value) { sourceSequence = value; }
    public UUID rawObservationId() { return rawObservationId; }
    public String status() { return status; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public UUID demoRunId() { return demoRunId; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public BigDecimal thresholdSeconds() { return thresholdSeconds; }
    public int bunchedGroupCount() { return bunchedGroupCount; }
    public boolean isProcessed() { return "PROCESSED".equals(status); }
    public boolean isRejected() { return "REJECTED".equals(status); }
    public void deferUntil(Instant value) { status = "DEFERRED"; nextAttemptAt = value; processedAt = null; rejectedAt = null; reason = null; }
    public void deferUntil(Instant value, String safeReason) {
        if (isProcessed() || isRejected()) return;
        if (nextAttemptAt == null || value.isAfter(nextAttemptAt)) nextAttemptAt = value;
        reason = java.util.Objects.requireNonNull(safeReason);
    }
    public void reject(Instant value, String valueReason) { status = "REJECTED"; rejectedAt = value; reason = valueReason; nextAttemptAt = null; }
}
