package com.csd.occ.ingestion.domain;

import java.util.List;

public record RoutePositionDirection(String operator, int directionId, double routeLengthM,
                                     List<RoutePositionBus> buses, int suppressedBusCount) {
    public RoutePositionDirection(String operator, int directionId, double routeLengthM, List<RoutePositionBus> buses) {
        this(operator, directionId, routeLengthM, buses, 0);
    }
}
