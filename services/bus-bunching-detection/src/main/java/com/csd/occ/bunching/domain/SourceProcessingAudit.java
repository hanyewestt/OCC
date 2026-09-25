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
        id = UUID.randomUUID();
        this.rawObservationId = rawObservationId;
        this.occurredAt = occurredAt;
        this.action = action;
        this.reason = reason;
        this.nextAttemptAt = nextAttemptAt;
        actor = "SYSTEM:BUS_BUNCHING_DETECTION";
        classification = "OBSERVATION_ONLY";
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
}
