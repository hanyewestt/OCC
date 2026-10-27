package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.RootCauseContextResponse;
import com.csd.occ.ingestion.domain.RootCauseContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/internal/v1/ingestion", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Planning context")
public class RootCauseContextController {
    private final RootCauseContextService contexts;

    public RootCauseContextController(RootCauseContextService contexts) { this.contexts = contexts; }

    @GetMapping("/demo-runs/{demoRunId}/planning-context")
    @Operation(operationId = "getSimulationPlanningContext", summary = "Get simulation evidence for a confirmed event")
    @ApiResponse(responseCode = "200", description = "Simulation-scoped evidence context")
    @ApiResponse(responseCode = "400", description = "Evidence observations do not match the simulation event",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Demo run not found")
    public RootCauseContextResponse find(@PathVariable UUID demoRunId, @RequestParam("at") Instant at,
            @RequestParam("serviceId") String serviceId, @RequestParam("stopCode") String stopCode,
            @RequestParam("directionId") Integer directionId,
            @Parameter(description = "Two to ten WQAP-2-owned arrival observation IDs")
            @RequestParam("arrivalObservationIds") List<UUID> arrivalObservationIds) {
        return response(contexts.find(demoRunId, at, serviceId, stopCode, directionId, arrivalObservationIds));
    }

    @GetMapping("/planning-context")
    @Operation(operationId = "getLivePlanningContext", summary = "Get retained live evidence for a confirmed event")
    @ApiResponse(responseCode = "200", description = "Observation-only evidence; requires the Planning peer token")
    @ApiResponse(responseCode = "400", description = "Missing, expired, mixed-scope or mismatched evidence")
    @ApiResponse(responseCode = "401", description = "Valid Planning peer token required")
    @ApiResponse(responseCode = "403", description = "Other authenticated principals cannot access peer evidence")
    public RootCauseContextResponse live(@RequestParam Instant at, @RequestParam String serviceId,
            @RequestParam String stopCode, @RequestParam Integer directionId,
            @RequestParam List<UUID> arrivalObservationIds) {
        return response(contexts.findLive(at, serviceId, stopCode, directionId, arrivalObservationIds));
    }

    private RootCauseContextResponse response(com.csd.occ.ingestion.domain.RootCauseContextSnapshot context) {
        return new RootCauseContextResponse(context.classification(), context.demoRunId(), context.referenceVersionId(),
                context.arrivals().stream().map(value -> new RootCauseContextResponse.ArrivalEvidenceResponse(
                        value.evidenceId(), value.observationId(), value.rawObservationId(), value.pollRunId(),
                        value.serviceId(), value.stopCode(), value.directionId(), value.estimatedArrivalAt(),
                        value.fetchedAt(), value.latitude(), value.longitude(), value.loadCategory(), value.vehicleType(),
                        value.classification(), value.demoRunId(), value.referenceVersionId())).toList(),
                context.incidents().stream().map(value -> new RootCauseContextResponse.IncidentEvidenceResponse(
                        value.evidenceId(), value.incidentId(), value.incidentType(), value.message(), value.latitude(),
                        value.longitude(), value.observedAt(), value.fetchedAt(), value.classification(), value.demoRunId())).toList(),
                context.missingFields());
    }
}
