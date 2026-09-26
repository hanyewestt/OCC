package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"serviceId", "operator", "category"})
public record BusRouteSummaryResponse(String serviceId, String operator, @Schema(nullable = true) String category) {
}
