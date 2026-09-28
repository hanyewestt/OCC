package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_processing_audit", schema = "bunching")
public class SourceProcessingAudit {
    protected SourceProcessingAudit() {
    }

    public SourceProcessingAudit(UUID rawObservationId, Instant occurredAt, String action, String reason,
                                 Instant nextAttemptAt) {
        this(rawObservationId, occurredAt, action, reason, nextAttemptAt, "OBSERVATION_ONLY", "LIVE", null);
    }

    public SourceProcessingAudit(UUID rawObservationId, Instant occurredAt, String action, String reason,
                                 Instant nextAttemptAt, String classification, String feedScope, UUID demoRunId) {
        id = UUID.randomUUID();
        this.rawObservationId = rawObservationId;
        this.occurredAt = occurredAt;
        this.action = action;
        this.reason = reason;
        this.nextAttemptAt = nextAttemptAt;
        actor = "SYSTEM:BUS_BUNCHING_DETECTION";
        this.classification = classification; this.feedScope = feedScope; this.demoRunId = demoRunId;
    }

    @Id
    private UUID id;
    private UUID rawObservationId;
    private String actor;
    private String action;
    private Instant occurredAt;
    private String reason;
    private Instant nextAttemptAt;
    private String classification;
    private String feedScope = "LIVE";
    private UUID demoRunId;
}
