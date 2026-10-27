package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoutePositionsResponse(String serviceId,
                                     @Schema(description = "UTC time when this response was computed")
                                     Instant computedAt,
                                     @Schema(nullable = true,
                                             description = "Provider poll cycle supplying the returned position evidence; null when the completed cycle has no usable readings")
                                     UUID sourcePollCycleId,
                                     @Schema(nullable = true,
                                             description = "Newest source reading time in the selected provider poll cycle; null when the completed cycle has no usable readings")
                                     Instant sourceObservedAt,
                                     long refreshIntervalSeconds,
                                     @Schema(description = "Maximum configured age of position-source observations, in seconds",
                                             minimum = "1")
                                     long maxSourceAgeSeconds,
                                     List<RoutePositionDirectionResponse> directions) {
}
