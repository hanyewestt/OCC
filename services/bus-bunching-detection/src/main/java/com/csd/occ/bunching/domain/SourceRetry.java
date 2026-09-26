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
        this.rawObservationId = rawObservationId;
        this.nextAttemptAt = nextAttemptAt;
    }

    @Id
    private UUID rawObservationId;
    private Instant nextAttemptAt;

    public UUID rawObservationId() { return rawObservationId; }
    public void deferUntil(Instant time) { if (time.isAfter(nextAttemptAt)) nextAttemptAt = time; }
}
