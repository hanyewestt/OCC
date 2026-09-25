package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(requiredProperties = {"stopCode", "roadName", "description", "latitude", "longitude"})
public record BusStopResponse(String stopCode, @Schema(nullable = true) String roadName,
                               @Schema(nullable = true) String description, @Schema(nullable = true) BigDecimal latitude,
                               @Schema(nullable = true) BigDecimal longitude) {
}
