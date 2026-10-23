package com.csd.occ.ingestion.domain;

import java.util.List;

public record RoutePositionDirection(String operator, int directionId, double routeLengthM,
                                     List<RoutePositionBus> buses) {
}
