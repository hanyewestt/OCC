package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record BusRouteResponse(String serviceId, List<BusRouteOperatorResponse> operators) {
}
