package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Pacing metrics from one calculation cycle. Not paginated: the set is bounded by the "
        + "monitored stops and services and must be delivered whole on every push.")
public record PacingSnapshotResponse(
        @Schema(description = "When this snapshot was calculated (UTC). If it stops advancing, the data is stale.",
                example = "2026-10-21T05:00:10Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant calculatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<PacingMetricResponse> items) {}
