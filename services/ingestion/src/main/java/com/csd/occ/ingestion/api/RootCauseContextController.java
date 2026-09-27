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
@RequestMapping(value = "/internal/v1/demo-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Root-cause evidence")
public class RootCauseContextController {
    private final RootCauseContextService contexts;

    public RootCauseContextController(RootCauseContextService contexts) { this.contexts = contexts; }

    @GetMapping("/{demoRunId}/root-cause-context")
    @Operation(operationId = "getSimulationRootCauseContext", summary = "Get simulation evidence for a confirmed event")
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
        var context = contexts.find(demoRunId, at, serviceId, stopCode, directionId, arrivalObservationIds);
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
