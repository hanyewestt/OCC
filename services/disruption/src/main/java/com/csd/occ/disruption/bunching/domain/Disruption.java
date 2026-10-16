package com.csd.occ.disruption.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disruption", schema = "disruption")
public class Disruption {
    protected Disruption() { }

    @Id private UUID id;
    private String kind;
    private String classification;
    private UUID demoRunId;
    private String status;
    private Instant openedAt;
    private Instant updatedAt;
    private Instant clearedAt;
}
