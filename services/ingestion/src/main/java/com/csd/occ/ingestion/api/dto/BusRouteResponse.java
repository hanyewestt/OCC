package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"serviceId", "operators"})
public record BusRouteResponse(String serviceId, List<BusRouteOperatorResponse> operators) {
}
