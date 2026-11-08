package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.RoutePositionBusResponse;
import com.csd.occ.ingestion.api.dto.RoutePositionDirectionResponse;
import com.csd.occ.ingestion.api.dto.RoutePositionsResponse;
import com.csd.occ.ingestion.domain.DemoRoutePositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/demo-runs/{demoRunId}/route-positions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demo runs")
public class DemoRoutePositionsController {
    private final DemoRoutePositionService positions;

    public DemoRoutePositionsController(DemoRoutePositionService positions) { this.positions = positions; }

    @GetMapping
    @Operation(operationId = "getDemoRoutePositions", summary = "Get the fictional fleet positions for a simulation run")
    @ApiResponse(responseCode = "200", description = "Simulation route positions returned")
    public RoutePositionsResponse positions(@PathVariable UUID demoRunId) {
        var snapshot = positions.positions(demoRunId);
        return new RoutePositionsResponse(snapshot.serviceId(), snapshot.computedAt(), null, null,
                snapshot.refreshIntervalSeconds(), snapshot.maxSourceAgeSeconds(), snapshot.directions().stream()
                .map(direction -> new RoutePositionDirectionResponse(direction.operator(), direction.directionId(),
                        direction.routeLengthM(), direction.buses().stream().map(bus -> new RoutePositionBusResponse(
                                bus.order(), bus.distanceAlongRouteM(), bus.uncertaintyM(), bus.speedMps(),
                                bus.evidenceObservationIds(), bus.positionEvidenceObservationIds(), bus.supportingStopCount())).toList(),
                        direction.suppressedBusCount())).toList());
    }
}
