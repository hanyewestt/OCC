package com.csd.occ.ingestion.api.dto;

import java.time.Instant;
import java.util.List;

public record RoutePositionsResponse(String serviceId, Instant computedAt, long refreshIntervalSeconds,
                                     List<RoutePositionDirectionResponse> directions) {
}
