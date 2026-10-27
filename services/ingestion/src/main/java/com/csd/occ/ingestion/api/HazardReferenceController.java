package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.BusRouteResponse;
import com.csd.occ.ingestion.api.dto.StationReferenceResponse;
import com.csd.occ.ingestion.domain.ReferenceTopologyQueryService;
import com.csd.occ.ingestion.domain.StationReferenceService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/ingestion")
public class HazardReferenceController {
    private final ReferenceTopologyQueryService topology;
    private final StationReferenceService stations;
    public HazardReferenceController(ReferenceTopologyQueryService topology, StationReferenceService stations) {
        this.topology = topology; this.stations = stations;
    }
    @GetMapping("/bus-routes/{serviceId}")
    @Operation(operationId = "getHazardRouteReference", summary = "Read route occurrences for hazard association",
            description = "Planning/Disruption peer capability required. Same topology as controller route reads.")
    public BusRouteResponse route(@PathVariable String serviceId) {
        return topology.busRoute(serviceId).map(BusRouteResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not resolved"));
    }
    @GetMapping("/stations/{code}")
    @Operation(operationId = "getHazardStationReference", summary = "Resolve a station reference",
            description = "Planning/Disruption peer capability required. Unknown codes return 404; coordinates may be null.")
    public StationReferenceResponse station(@PathVariable String code) {
        return stations.find(code).map(station -> new StationReferenceResponse(station.code(), station.name(),
                station.latitude(), station.longitude(), station.versionChecksum()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not resolved"));
    }
}
