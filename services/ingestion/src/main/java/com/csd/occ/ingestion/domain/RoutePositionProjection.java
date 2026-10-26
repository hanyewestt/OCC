package com.csd.occ.ingestion.domain;

/** One possible route-segment occurrence for a reported bus coordinate. */
public record RoutePositionProjection(int segmentEndSequence, double distanceAlongRouteM,
                                      double referenceDistanceAlongRouteM, double offsetM) {
}
