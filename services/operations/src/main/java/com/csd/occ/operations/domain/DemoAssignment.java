package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_assignment", schema = "operations")
public class DemoAssignment {
    @Id private UUID id;
    private UUID runId;
    private UUID vehicleId;
    private UUID driverId;
    private Instant createdAt;

    protected DemoAssignment() { }

    public DemoAssignment(UUID id, UUID runId, UUID vehicleId, UUID driverId, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getDriverId() { return driverId; }
}
