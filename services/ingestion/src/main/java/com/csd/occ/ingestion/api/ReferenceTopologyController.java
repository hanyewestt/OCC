package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.BusRouteDirectionResponse;
import com.csd.occ.ingestion.api.dto.BusRouteOperatorResponse;
import com.csd.occ.ingestion.api.dto.BusRouteResponse;
import com.csd.occ.ingestion.api.dto.BusRouteStopResponse;
import com.csd.occ.ingestion.api.dto.BusRouteSummaryResponse;
import com.csd.occ.ingestion.api.dto.BusStopResponse;
import com.csd.occ.ingestion.api.dto.PageResponse;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.domain.ReferenceTopologyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reference topology")
public class ReferenceTopologyController {
    private final ReferenceTopologyQueryService queries;

    public ReferenceTopologyController(ReferenceTopologyQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/bus-routes")
    @Operation(operationId = "listBusRoutes", summary = "List bus routes in the active reference topology")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No active reference topology",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PageResponse<BusRouteSummaryResponse> busRoutes(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return page(queries.busRoutes(PageRequest.of(page, size)), this::routeSummaryResponse);
    }

    @GetMapping("/bus-routes/{serviceId}")
    @Operation(operationId = "getBusRoute", summary = "Get all ordered stop sequences for a bus service")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Bus service or active reference topology not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))))
    public BusRouteResponse busRoute(@PathVariable String serviceId) {
        return queries.busRoute(serviceId).map(this::routeResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bus service not found"));
    }

    @GetMapping("/bus-stops")
    @Operation(operationId = "listBusStops", summary = "List bus stops in the active reference topology")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No active reference topology",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PageResponse<BusStopResponse> busStops(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return page(queries.busStops(PageRequest.of(page, size)), this::stopResponse);
    }

    @GetMapping("/bus-stops/{stopCode}")
    @Operation(operationId = "getBusStop", summary = "Get a bus stop by stop code")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "Bus stop or active reference topology not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))))
    public BusStopResponse busStop(@PathVariable String stopCode) {
        return queries.busStop(stopCode).map(this::stopResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bus stop not found"));
    }

    private BusRouteSummaryResponse routeSummaryResponse(ReferenceTopologyPort.BusRouteSummary route) {
        return new BusRouteSummaryResponse(route.serviceId(), route.operator(), route.category());
    }

    private BusRouteResponse routeResponse(ReferenceTopologyPort.BusRoute route) {
        return new BusRouteResponse(route.serviceId(), route.operators().stream().map(operator ->
                new BusRouteOperatorResponse(operator.operator(), operator.category(), operator.directions().stream()
                        .map(direction -> new BusRouteDirectionResponse(direction.directionId(), direction.stops().stream()
                                .map(stop -> new BusRouteStopResponse(stop.stopCode(), stop.roadName(), stop.description(),
                                        stop.latitude(), stop.longitude(), stop.stopSequence(), stop.distanceKm()))
                                .toList())).toList())).toList());
    }

    private BusStopResponse stopResponse(ReferenceTopologyPort.BusStop stop) {
        return new BusStopResponse(stop.stopCode(), stop.roadName(), stop.description(), stop.latitude(), stop.longitude());
    }

    private <T, R> PageResponse<R> page(Page<T> source, java.util.function.Function<T, R> mapper) {
        return new PageResponse<>(source.getContent().stream().map(mapper).toList(), source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
}
