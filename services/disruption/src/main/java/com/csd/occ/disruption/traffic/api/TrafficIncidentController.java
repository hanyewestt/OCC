package com.csd.occ.disruption.traffic.api;

import com.csd.occ.disruption.traffic.api.dto.TrafficIncidentPageResponse;
import com.csd.occ.disruption.traffic.api.dto.TrafficIncidentResponse;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentReadService;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/disruption/traffic-incidents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Traffic incidents")
public class TrafficIncidentController {
    private final TrafficIncidentReadService incidents;

    public TrafficIncidentController(TrafficIncidentReadService incidents) { this.incidents = incidents; }

    @GetMapping
    @Operation(operationId = "listActiveTrafficIncidents", summary = "List active detected traffic incidents")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of active traffic incidents",
            content = @Content(schema = @Schema(implementation = TrafficIncidentPageResponse.class))))
    public TrafficIncidentPageResponse currentOpen(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum incidents per page (1-200)") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Page<TrafficIncidentState> result = incidents.currentOpen(page, size);
        return new TrafficIncidentPageResponse(result.getContent().stream().map(this::response).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private TrafficIncidentResponse response(TrafficIncidentState incident) {
        return new TrafficIncidentResponse(incident.id(), incident.disruptionId(), incident.classification(),
                incident.demoRunId(), incident.incidentType(), incident.message(), incident.latitude(),
                incident.longitude(), incident.observedAt(), incident.fetchedAt(), incident.openedAt(), incident.updatedAt());
    }
}
