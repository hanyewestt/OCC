package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record RoutePositionBusResponse(
                                      @Schema(description = "Display order among estimates in this route direction")
                                      int order,
                                      @Schema(description = "Approximate distance from the start of the ordered display route, in meters")
                                      double distanceAlongRouteM,
                                      @Schema(description = "Configured half-width of the displayed position interval, in meters")
                                      double uncertaintyM,
                                      @Schema(nullable = true,
                                              description = "Estimated speed in meters per second; null when the latest-cycle evidence does not establish speed")
                                      Double speedMps,
                                      @Schema(description = "Arrival observation IDs used to link or score this estimate; these are audit evidence records, not physical vehicle IDs")
                                      List<UUID> evidenceObservationIds,
                                      @Schema(description = "Subset of evidence observation IDs whose coordinate version determines the displayed position interval")
                                      List<UUID> positionEvidenceObservationIds,
                                      @Schema(description = "Number of distinct stops whose observations support this estimate")
                                      int supportingStopCount) {
    public RoutePositionBusResponse(int order, double distanceAlongRouteM, double uncertaintyM,
            Double speedMps, List<UUID> evidenceObservationIds) {
        this(order, distanceAlongRouteM, uncertaintyM, speedMps,
                evidenceObservationIds, evidenceObservationIds, 0);
    }
}
