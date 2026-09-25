package com.csd.occ.operations.api;

import com.csd.occ.operations.api.dto.CreateDemoRunRequest;
import com.csd.occ.operations.api.dto.DemoRunResponse;
import com.csd.occ.operations.api.dto.DemoVehiclePageResponse;
import com.csd.occ.operations.api.dto.DemoVehicleResponse;
import com.csd.occ.operations.api.dto.PlaceDemoVehicleRequest;
import com.csd.occ.operations.domain.DemoFleetService;
import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import com.csd.occ.operations.domain.DemoRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/demo-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demo fleet")
@SecurityRequirement(name = "bearerAuth")
public class DemoFleetController {
    private final DemoFleetService fleet;
    private final DemoFleetPagePolicy pagePolicy;

    public DemoFleetController(DemoFleetService fleet, DemoFleetPagePolicy pagePolicy) {
        this.fleet = fleet;
        this.pagePolicy = pagePolicy;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CONTROLLER')")
    @Operation(operationId = "createDemoRun", summary = "Create a draft simulated run")
    @ApiResponse(responseCode = "201", description = "Draft created",
            headers = @Header(name = "Location", description = "URI of the new run"),
            content = @Content(schema = @Schema(implementation = DemoRunResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid service or direction in active route topology",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Ingestion route topology is unavailable",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DemoRunResponse> createRun(@Valid @RequestBody CreateDemoRunRequest request,
            Authentication actor) {
        DemoRun run = fleet.createRun(request.serviceId().trim(), request.directionId(), actor.getName());
        return ResponseEntity.created(URI.create("/api/v1/demo-runs/" + run.getId())).body(response(run));
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN','CONTROLLER')")
    @Operation(operationId = "getDemoRun", summary = "Get a draft simulated run")
    public DemoRunResponse run(@PathVariable UUID runId) { return response(fleet.run(runId)); }

    @PostMapping(value = "/{runId}/vehicles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CONTROLLER')")
    @Operation(operationId = "placeDemoVehicle", summary = "Place a demo vehicle and assign a demo driver")
    @ApiResponse(responseCode = "201", description = "Vehicle and assignment created",
            headers = @Header(name = "Location", description = "URI of the new vehicle"),
            content = @Content(schema = @Schema(implementation = DemoVehicleResponse.class)))
    @ApiResponse(responseCode = "409", description = "Run is not a draft, or label/plate already exists",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "Invalid vehicle input or position outside selected route's stop proximity",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Ingestion route topology is unavailable",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DemoVehicleResponse> place(@PathVariable UUID runId,
            @Valid @RequestBody PlaceDemoVehicleRequest request) {
        var placement = fleet.place(runId, request.displayLabel().trim(), request.syntheticPlate(),
                request.latitude(), request.longitude(), request.driverDisplayName().trim());
        return ResponseEntity.created(URI.create("/api/v1/demo-runs/" + runId + "/vehicles/"
                + placement.vehicle().getId())).body(response(placement));
    }

    @GetMapping("/{runId}/vehicles")
    @PreAuthorize("hasAnyRole('ADMIN','CONTROLLER')")
    @Operation(operationId = "listDemoVehicles", summary = "List a run's demo vehicles and assignments")
    @ApiResponse(responseCode = "400", description = "Invalid page or page size",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public DemoVehiclePageResponse vehicles(@PathVariable UUID runId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Items per page; omitted uses the configured default and values above the configured maximum are rejected")
            @RequestParam(required = false) @Min(1) Integer size) {
        int requestedSize = size == null ? pagePolicy.defaultSize() : size;
        if (requestedSize > pagePolicy.maxSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size exceeds configured maximum");
        }
        var result = fleet.placements(runId, PageRequest.of(page, requestedSize));
        return new DemoVehiclePageResponse(result.getContent().stream().map(this::response).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{runId}/vehicles/{vehicleId}")
    @PreAuthorize("hasAnyRole('ADMIN','CONTROLLER')")
    @Operation(operationId = "getDemoVehicle", summary = "Get one demo vehicle and assignment")
    public DemoVehicleResponse vehicle(@PathVariable UUID runId, @PathVariable UUID vehicleId) {
        return response(fleet.placement(runId, vehicleId));
    }

    private DemoRunResponse response(DemoRun run) {
        return new DemoRunResponse(run.getId(), run.getServiceId(), run.getDirectionId(),
                run.getStatus(), run.getCreatedAt(), "SIMULATION");
    }

    private DemoVehicleResponse response(DemoFleetService.Placement placement) {
        var assignment = placement.assignment();
        var vehicle = placement.vehicle();
        var driver = placement.driver();
        return new DemoVehicleResponse(assignment.getId(), assignment.getRunId(), vehicle.getId(),
                driver.getId(), vehicle.getDisplayLabel(), vehicle.getSyntheticPlate(), driver.getDisplayName(),
                vehicle.getLatitude(), vehicle.getLongitude(), vehicle.getCreatedAt(), "SIMULATION");
    }
}
