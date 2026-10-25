package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"serviceId", "operators"})
public record BusRouteResponse(String serviceId, List<BusRouteOperatorResponse> operators) {
    public static BusRouteResponse from(com.csd.occ.ingestion.domain.ReferenceTopologyPort.BusRoute route) {
        return new BusRouteResponse(route.serviceId(), route.operators().stream().map(operator ->
                new BusRouteOperatorResponse(operator.operator(), operator.category(), operator.directions().stream()
                        .map(direction -> new BusRouteDirectionResponse(direction.directionId(), direction.stops().stream()
                                .map(stop -> new BusRouteStopResponse(stop.stopCode(), stop.roadName(), stop.description(),
                                        stop.latitude(), stop.longitude(), stop.stopSequence(), stop.distanceKm())).toList())).toList())).toList());
    }
}
