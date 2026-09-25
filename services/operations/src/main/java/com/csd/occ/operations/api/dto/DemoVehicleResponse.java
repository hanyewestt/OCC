package com.csd.occ.operations.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DemoVehicleResponse(UUID assignmentId, UUID runId, UUID vehicleId,
        UUID driverId, String displayLabel, @Schema(nullable = true) String syntheticPlate,
        String driverDisplayName, BigDecimal latitude, BigDecimal longitude,
        Instant createdAt, String classification) { }
