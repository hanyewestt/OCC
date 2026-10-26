package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RoutePositionDirectionResponse(String operator, int directionId, double routeLengthM,
                                             List<RoutePositionBusResponse> buses,
                                             @Schema(description = "Compatibility count of otherwise valid estimates omitted from this response; zero for the latest-cycle estimator")
                                             int suppressedBusCount) {
    public RoutePositionDirectionResponse(String operator, int directionId, double routeLengthM,
            List<RoutePositionBusResponse> buses) {
        this(operator, directionId, routeLengthM, buses, 0);
    }
}
