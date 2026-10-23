package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record RoutePositionBusResponse(int order,
                                      @Schema(description = "Approximate distance from the start of the ordered route, in meters")
                                      double distanceAlongRouteM,
                                      @Schema(description = "Estimated position uncertainty in meters")
                                      double uncertaintyM,
                                      @Schema(nullable = true, description = "Estimated speed in meters per second; null until two polls can be matched")
                                      Double speedMps) {
}
