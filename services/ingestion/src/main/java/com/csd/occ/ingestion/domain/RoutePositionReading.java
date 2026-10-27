package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One predicted bus from one stop's raw DataMall response. */
public record RoutePositionReading(UUID observationId, UUID snapshotId, UUID pollCycleId, String stopCode, int stopSequence,
                                   int visitOrder, double distanceAlongRouteM, double stopDistanceAlongRouteM,
                                   double latitude, double longitude, String originCode, String destinationCode,
                                   int visitNumber, List<Double> stopReferenceDistancesM,
                                   List<RoutePositionProjection> projectionCandidates,
                                   Instant estimatedArrivalAt, Instant fetchedAt) {
    public RoutePositionReading {
        stopReferenceDistancesM = List.copyOf(stopReferenceDistancesM);
        projectionCandidates = List.copyOf(projectionCandidates);
    }

    public RoutePositionReading(UUID observationId, UUID snapshotId, UUID pollCycleId, String stopCode,
            int stopSequence, int visitOrder, double distanceAlongRouteM, double stopDistanceAlongRouteM,
            Instant estimatedArrivalAt, Instant fetchedAt) {
        this(observationId, snapshotId, pollCycleId, stopCode, stopSequence, visitOrder,
                distanceAlongRouteM, stopDistanceAlongRouteM, Double.NaN, Double.NaN, "", "", 1,
                List.of(stopDistanceAlongRouteM),
                List.of(new RoutePositionProjection(stopSequence, distanceAlongRouteM,
                        distanceAlongRouteM, 0)), estimatedArrivalAt, fetchedAt);
    }
}
