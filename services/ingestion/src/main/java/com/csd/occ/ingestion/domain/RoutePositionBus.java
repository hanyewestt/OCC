package com.csd.occ.ingestion.domain;

public record RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps) {
}
