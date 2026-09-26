package com.csd.occ.ingestion.domain;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
@Embeddable
public record DemoVehicleAssignmentId(UUID demoRunId, String tripId) implements Serializable {}
