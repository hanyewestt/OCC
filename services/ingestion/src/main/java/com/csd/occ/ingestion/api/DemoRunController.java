package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.DemoBunchingAlertResponse;
import com.csd.occ.ingestion.api.dto.DemoRunResponse;
import com.csd.occ.ingestion.api.dto.DemoRecommendationResponse;
import com.csd.occ.ingestion.api.dto.DemoVehicleResponse;
import com.csd.occ.ingestion.domain.DemoAlertDetails;
import com.csd.occ.ingestion.domain.DemoRunDetails;
import com.csd.occ.ingestion.domain.DemoRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/demo-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demo runs")
public class DemoRunController {
    private final DemoRunService runs;

    public DemoRunController(DemoRunService runs) { this.runs = runs; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "startDemoRun", summary = "Start a deterministic simulation bunching run")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Demo run started"),
            @ApiResponse(responseCode = "409", description = "A demo run is already active"),
            @ApiResponse(responseCode = "503", description = "Bunching control is unavailable")})
    public DemoRunResponse start() { return response(runs.start()); }

    @GetMapping("/active")
    @Operation(operationId = "getActiveDemoRun", summary = "Get the active simulation run for page recovery")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "An active run exists"),
            @ApiResponse(responseCode = "204", description = "No active run")})
    public ResponseEntity<DemoRunResponse> active() {
        return runs.active().map(value -> ResponseEntity.ok(response(value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getDemoRun", summary = "Get run status and simulation alerts",
            description = "Closed and failed runs older than the configured operational retention window return 404, including before physical cleanup. Active replay runs remain available.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Run status returned"),
            @ApiResponse(responseCode = "404", description = "Demo run not found"),
            @ApiResponse(responseCode = "503", description = "Bunching status is unavailable")})
    public DemoRunResponse status(@PathVariable UUID id) { return response(runs.status(id)); }

    @GetMapping("/{id}/recommendation")
    @Operation(operationId = "getDemoRecommendation", summary = "Get the recommendation produced for a simulation bunching event")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Recommendation output returned"),
            @ApiResponse(responseCode = "204", description = "Recommendation is still being generated"),
            @ApiResponse(responseCode = "404", description = "Demo run not found"),
            @ApiResponse(responseCode = "503", description = "Recommendation service is unavailable")})
    public ResponseEntity<DemoRecommendationResponse> recommendation(@PathVariable UUID id) {
        return runs.recommendation(id).map(DemoRecommendationResponse::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/close")
    @Operation(operationId = "closeDemoRun", summary = "Return bunching consumption to the live feed")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Run closed and live consumption resumed"),
            @ApiResponse(responseCode = "404", description = "Demo run not found"),
            @ApiResponse(responseCode = "503", description = "Bunching control is unavailable")})
    public DemoRunResponse close(@PathVariable UUID id) { return response(runs.close(id)); }

    private static DemoRunResponse response(DemoRunDetails run) {
        return new DemoRunResponse(run.id(), run.status(), run.currentStep(), run.alerts().stream()
                .map(DemoRunController::alert).toList());
    }

    private static DemoBunchingAlertResponse alert(DemoAlertDetails alert) {
        return new DemoBunchingAlertResponse(alert.eventId(), alert.bunchingEventId(), alert.action(), alert.classification(), alert.serviceId(),
                alert.stopCode(), alert.stopName(), alert.stopRoadName(), alert.directionId(), alert.detectedAt(),
                alert.headwaySeconds(), vehicle(alert.leadingVehicle()),
                vehicle(alert.followingVehicle()));
    }

    private static DemoVehicleResponse vehicle(com.csd.occ.ingestion.domain.DemoVehicleDetails value) {
        return new DemoVehicleResponse(value.registrationNumber(), value.operator(), value.vehicleType(), value.captainCode());
    }
}
