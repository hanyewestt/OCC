package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Pacing for one bus: its gap to the bus ahead and the bus behind")
public record PacingMetricResponse(
        @Schema(description = "Simulation vehicle registration, or for live data a queue-position reference "
                + "(LIVE-<service>-D<direction>-<stop>-<position>) that is only stable within one snapshot "
                + "because Bus Arrival does not identify vehicles",
                example = "DEMO-BUS-190-02", requiredMode = Schema.RequiredMode.REQUIRED)
        String vehicleReference,
        @Schema(description = "SIMULATION for demo replay data; OBSERVATION_ONLY for live provider data",
                allowableValues = {"SIMULATION", "OBSERVATION_ONLY"}, example = "SIMULATION",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String classification,
        @Schema(description = "Stop whose arrival predictions produced this metric", example = "44251",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String stopCode,
        @Schema(example = "190", requiredMode = Schema.RequiredMode.REQUIRED)
        String serviceId,
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        int directionId,
        @Schema(description = "Configured target headway, in seconds", example = "180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long targetHeadwaySeconds,
        @Schema(description = "Gap to the bus ahead; null when no bus ahead is in the same prediction set",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        PacingGapResponse ahead,
        @Schema(description = "Gap to the bus behind; null when no bus behind is in the same prediction set",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        PacingGapResponse behind,
        @Schema(description = "When the underlying predictions were fetched (UTC)",
                example = "2026-10-21T05:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant observedAt) {}
