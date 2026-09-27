package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_retry", schema = "bunching")
public class SourceRetry {
    protected SourceRetry() {
    }

    public SourceRetry(UUID rawObservationId, Instant nextAttemptAt) {
        this(rawObservationId, nextAttemptAt, "LIVE", null);
    }

    public SourceRetry(UUID rawObservationId, Instant nextAttemptAt, String feedScope, UUID demoRunId) {
        this.rawObservationId = rawObservationId; this.nextAttemptAt = nextAttemptAt;
        this.feedScope = feedScope; this.demoRunId = demoRunId;
    }

    @Id
    private UUID rawObservationId;
    private Instant nextAttemptAt;
    private String feedScope = "LIVE";
    private UUID demoRunId;

    public UUID rawObservationId() { return rawObservationId; }
    public String feedScope() { return feedScope; }
    public UUID demoRunId() { return demoRunId; }
    public void deferUntil(Instant time) { if (time.isAfter(nextAttemptAt)) nextAttemptAt = time; }
}
