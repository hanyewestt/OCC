package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public record RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps,
                               List<UUID> evidenceObservationIds) {
    public RoutePositionBus {
        evidenceObservationIds = List.copyOf(evidenceObservationIds);
    }

    public RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps, List.of());
    }
}
