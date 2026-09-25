package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_vehicle", schema = "operations")
public class DemoVehicle {
    @Id private UUID id;
    private UUID runId;
    private String displayLabel;
    private String syntheticPlate;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Instant createdAt;
    private String classification;

    protected DemoVehicle() { }

    public DemoVehicle(UUID id, UUID runId, String displayLabel, String syntheticPlate,
            BigDecimal latitude, BigDecimal longitude, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.displayLabel = displayLabel;
        this.syntheticPlate = syntheticPlate;
        this.latitude = latitude;
        this.longitude = longitude;
        this.createdAt = createdAt;
        this.classification = "SIMULATION";
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getDisplayLabel() { return displayLabel; }
    public String getSyntheticPlate() { return syntheticPlate; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public Instant getCreatedAt() { return createdAt; }
    public String getClassification() { return classification; }
}
