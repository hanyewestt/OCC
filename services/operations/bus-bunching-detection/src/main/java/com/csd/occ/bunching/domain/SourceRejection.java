package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_rejection", schema = "bunching")
public class SourceRejection {
    protected SourceRejection() {
    }

    public SourceRejection(UUID rawObservationId, Instant rejectedAt, String reason) {
        this(rawObservationId, rejectedAt, reason, "OBSERVATION_ONLY", "LIVE", null);
    }

    public SourceRejection(UUID rawObservationId, Instant rejectedAt, String reason, String classification,
                           String feedScope, UUID demoRunId) {
        this.rawObservationId = rawObservationId;
        this.rejectedAt = rejectedAt;
        this.reason = reason;
        this.classification = classification; this.feedScope = feedScope; this.demoRunId = demoRunId;
    }

    @Id
    private UUID rawObservationId;
    private Instant rejectedAt;
    private String reason;
    private String classification = "OBSERVATION_ONLY";
    private String feedScope = "LIVE";
    private UUID demoRunId;
}
