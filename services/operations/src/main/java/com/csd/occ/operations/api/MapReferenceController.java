package com.csd.occ.operations.api;

import com.csd.occ.operations.domain.MapReferencePort;
import com.csd.occ.operations.api.dto.MapReferenceResponse;
import io.swagger.v3.oas.annotations.Operation;
import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/map")
public class MapReferenceController {
    private final MapReferencePort reference;
    private final DemoFleetPagePolicy pages;
    public MapReferenceController(MapReferencePort reference, DemoFleetPagePolicy pages) {
        this.reference = reference; this.pages = pages;
    }
    private int limit(Integer size) {
        int limit = size == null ? pages.defaultSize() : size;
        // Ingestion's public reference/arrival API accepts at most 200 records per page.
        if (limit > Math.min(pages.maxSize(), 200)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size exceeds maximum");
        return limit;
    }
    @GetMapping("/services")
    @Operation(operationId = "listMapServices", summary = "List imported bus services for map selection")
    public MapReferenceResponse.ServicePage services(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var result = reference.services(page, limit(size));
        return new MapReferenceResponse.ServicePage(result.items().stream().map(item ->
                new MapReferenceResponse.Service(item.serviceId(), item.operator(), item.category())).toList(),
                result.page(), result.size(), result.totalItems(), result.totalPages());
    }
    @GetMapping("/routes/{serviceId}")
    @Operation(operationId = "getMapRoute", summary = "Read route directions and named bus stops")
    public MapReferenceResponse.Route route(@PathVariable String serviceId) {
        var route = reference.route(serviceId);
        return new MapReferenceResponse.Route(route.serviceId(), route.operators().stream().map(operator ->
                new MapReferenceResponse.Operator(operator.operator(), operator.directions().stream().map(direction ->
                        new MapReferenceResponse.Direction(direction.directionId(), direction.stops().stream().map(stop ->
                                new MapReferenceResponse.Stop(stop.stopCode(), stop.roadName(), stop.description(),
                                        stop.latitude(), stop.longitude(), stop.stopSequence())).toList())).toList())).toList());
    }
    @GetMapping("/arrivals")
    @Operation(operationId = "listMapStopArrivals", summary = "Read stored next arrivals for a service and stop",
            description = "Does not make a DataMall request. Predictions may be old; fetchedAt and ageSeconds identify their freshness. Includes predictions without vehicle GPS.")
    public MapReferenceResponse.ArrivalPage arrivals(@RequestParam String serviceId, @RequestParam String stopCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var result = reference.arrivals(serviceId, stopCode, page, limit(size));
        return new MapReferenceResponse.ArrivalPage(result.items().stream().map(item ->
                new MapReferenceResponse.Arrival(item.stopCode(), item.serviceId(), item.directionId(), item.visitOrder(),
                        item.estimatedArrivalAt(), item.fetchedAt(), item.ageSeconds())).toList(),
                result.page(), result.size(), result.totalItems(), result.totalPages());
    }
}
