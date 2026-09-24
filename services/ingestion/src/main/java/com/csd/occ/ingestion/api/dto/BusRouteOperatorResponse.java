package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record BusRouteOperatorResponse(String operator, String category, List<BusRouteDirectionResponse> directions) {
}
