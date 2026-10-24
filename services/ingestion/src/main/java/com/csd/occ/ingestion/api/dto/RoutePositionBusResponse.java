package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record RoutePositionBusResponse(int order,
                                      @Schema(description = "Approximate distance from the start of the ordered route, in meters")
                                      double distanceAlongRouteM,
                                      @Schema(description = "Estimated position uncertainty in meters")
                                      double uncertaintyM,
                                      @Schema(nullable = true, description = "Estimated speed in meters per second; null until two polls can be matched")
                                      Double speedMps,
                                      @Schema(description = "Arrival observation IDs that contributed to this approximate position; these do not identify a physical vehicle")
                                      List<UUID> evidenceObservationIds,
                                      @Schema(description = "Number of distinct stops supporting this estimate")
                                      int supportingStopCount) {
    public RoutePositionBusResponse(int order, double distanceAlongRouteM, double uncertaintyM,
            Double speedMps, List<UUID> evidenceObservationIds) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps, evidenceObservationIds, 0);
    }
}
