package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

/** One predicted bus from one stop's raw DataMall response. */
public record RoutePositionReading(UUID observationId, UUID snapshotId, UUID pollCycleId, String stopCode, int stopSequence,
                                   int visitOrder, double distanceAlongRouteM, double stopDistanceAlongRouteM,
                                   Instant estimatedArrivalAt, Instant fetchedAt) {
}
