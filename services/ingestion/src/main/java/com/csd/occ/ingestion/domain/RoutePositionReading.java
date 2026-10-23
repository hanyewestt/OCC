package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record RoutePositionReading(UUID observationId, double distanceAlongRouteM, Instant fetchedAt) {
    public RoutePositionReading(double distanceAlongRouteM, Instant fetchedAt) {
        this(null, distanceAlongRouteM, fetchedAt);
    }
}
