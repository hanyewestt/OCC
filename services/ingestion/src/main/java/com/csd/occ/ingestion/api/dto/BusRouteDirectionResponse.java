package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"directionId", "stops"})
public record BusRouteDirectionResponse(int directionId, List<BusRouteStopResponse> stops) {
}
