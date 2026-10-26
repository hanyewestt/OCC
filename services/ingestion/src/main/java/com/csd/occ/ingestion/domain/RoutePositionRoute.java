package com.csd.occ.ingestion.domain;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record RoutePositionRoute(String operator, int directionId, double routeLengthM,
                                 boolean circular, double referenceLengthM,
                                 UUID selectedPollCycleId,
                                 List<RoutePositionReading> readings) {
    public RoutePositionRoute(String operator, int directionId, double routeLengthM,
            boolean circular, double referenceLengthM, List<RoutePositionReading> readings) {
        this(operator, directionId, routeLengthM, circular, referenceLengthM,
                latestPollCycleId(readings), readings);
    }

    public RoutePositionRoute(String operator, int directionId, double routeLengthM,
            List<RoutePositionReading> readings) {
        this(operator, directionId, routeLengthM, false, routeLengthM,
                latestPollCycleId(readings), readings);
    }

    private static UUID latestPollCycleId(List<RoutePositionReading> readings) {
        return readings.stream()
                .filter(reading -> reading.pollCycleId() != null && reading.fetchedAt() != null)
                .max(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparing(reading -> reading.pollCycleId().toString()))
                .map(RoutePositionReading::pollCycleId)
                .orElse(null);
    }
}
