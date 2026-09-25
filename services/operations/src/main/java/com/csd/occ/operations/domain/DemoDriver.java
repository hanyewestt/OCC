package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_driver", schema = "operations")
public class DemoDriver {
    @Id private UUID id;
    private UUID runId;
    private String displayName;
    private Instant createdAt;

    protected DemoDriver() { }

    public DemoDriver(UUID id, UUID runId, String displayName, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getDisplayName() { return displayName; }
}
