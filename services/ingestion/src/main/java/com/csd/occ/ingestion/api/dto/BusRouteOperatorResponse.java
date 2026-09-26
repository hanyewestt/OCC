package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"operator", "category", "directions"})
public record BusRouteOperatorResponse(String operator, @Schema(nullable = true) String category,
                                       List<BusRouteDirectionResponse> directions) {
}
