package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record RoutePositionDirectionResponse(String operator, int directionId, double routeLengthM,
                                             List<RoutePositionBusResponse> buses, int suppressedBusCount) {
    public RoutePositionDirectionResponse(String operator, int directionId, double routeLengthM,
            List<RoutePositionBusResponse> buses) {
        this(operator, directionId, routeLengthM, buses, 0);
    }
}
