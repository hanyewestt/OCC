package com.csd.occ.disruption.traffic.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An active traffic incident detected from completed provider observations")
public record TrafficIncidentResponse(
        UUID id,
        UUID disruptionId,
        String classification,
        UUID demoRunId,
        String incidentType,
        String message,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant observedAt,
        Instant fetchedAt,
        Instant openedAt,
        Instant updatedAt) { }
