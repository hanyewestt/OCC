package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_run", schema = "operations")
public class DemoRun {
    @Id private UUID id;
    private String serviceId;
    private Short directionId;
    private String status;
    private String createdBySub;
    private Instant createdAt;
    @Version private Long version;

    protected DemoRun() { }

    public DemoRun(UUID id, String serviceId, int directionId, String createdBySub, Instant createdAt) {
        this.id = id;
        this.serviceId = serviceId;
        this.directionId = (short) directionId;
        this.status = "DRAFT";
        this.createdBySub = createdBySub;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getServiceId() { return serviceId; }
    public int getDirectionId() { return directionId; }
    public String getStatus() { return status; }
    public String getCreatedBySub() { return createdBySub; }
    public Instant getCreatedAt() { return createdAt; }
}
