package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public record RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps,
                               List<UUID> evidenceObservationIds, List<UUID> positionEvidenceObservationIds,
                               int supportingStopCount) {
    public RoutePositionBus {
        evidenceObservationIds = List.copyOf(evidenceObservationIds);
        positionEvidenceObservationIds = List.copyOf(positionEvidenceObservationIds);
    }

    public RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps, List.of(), List.of(), 0);
    }

    public RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps,
            List<UUID> evidenceObservationIds) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps,
                evidenceObservationIds, evidenceObservationIds, 0);
    }

    public RoutePositionBus(int order, double distanceAlongRouteM, double uncertaintyM, Double speedMps,
            List<UUID> evidenceObservationIds, int supportingStopCount) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps,
                evidenceObservationIds, evidenceObservationIds, supportingStopCount);
    }
}
