package com.csd.occ.ingestion.domain;

import java.util.List;

public record RoutePositionRoute(String operator, int directionId, double routeLengthM,
                                 List<RoutePositionReading> readings) {
}
