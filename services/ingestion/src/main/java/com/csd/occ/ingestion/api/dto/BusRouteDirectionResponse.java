package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record BusRouteDirectionResponse(int directionId, List<BusRouteStopResponse> stops) {
}
