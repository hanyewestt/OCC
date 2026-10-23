package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.RoutePositionBusResponse;
import com.csd.occ.ingestion.api.dto.RoutePositionDirectionResponse;
import com.csd.occ.ingestion.api.dto.RoutePositionsResponse;
import com.csd.occ.ingestion.domain.RoutePositionSnapshot;
import com.csd.occ.ingestion.domain.RoutePositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Route positions")
public class RoutePositionsController {
    private final RoutePositionService positions;

    public RoutePositionsController(RoutePositionService positions) {
        this.positions = positions;
    }

    @GetMapping("/route-positions")
    @Operation(operationId = "getRoutePositions", summary = "Estimate bus positions from recent route observations",
            description = "Returns temporary order-based position estimates. They do not identify vehicles or prove that observations belong to the same bus.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estimated positions returned",
                    content = @Content(schema = @Schema(implementation = RoutePositionsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid or missing service ID",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Service or active route topology not found",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public RoutePositionsResponse routePositions(@RequestParam("service") @NotBlank @Size(max = 20) String serviceId) {
        String normalizedService = serviceId.trim();
        RoutePositionSnapshot snapshot = positions.positions(normalizedService)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bus route not found"));
        return new RoutePositionsResponse(snapshot.serviceId(), snapshot.computedAt(), snapshot.refreshIntervalSeconds(),
                snapshot.directions().stream().map(direction -> new RoutePositionDirectionResponse(
                        direction.operator(), direction.directionId(), direction.routeLengthM(), direction.buses().stream()
                        .map(bus -> new RoutePositionBusResponse(bus.order(), bus.distanceAlongRouteM(),
                                bus.uncertaintyM(), bus.speedMps())).toList())).toList());
    }
}
