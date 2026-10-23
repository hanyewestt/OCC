package com.csd.occ.ingestion.domain;

import java.time.Instant;

public record RoutePositionReading(double distanceAlongRouteM, Instant fetchedAt) {
}
