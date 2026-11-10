package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.SimulationBunchingGroupResponse;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.CurrentSimulationBunchingGroupService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/ingestion/demo-runs/{runId}/bunching-groups")
public class SimulationBunchingGroupController {
    private final DemoRunService runs;
    private final CurrentSimulationBunchingGroupService current;

    public SimulationBunchingGroupController(DemoRunService runs, CurrentSimulationBunchingGroupService current) {
        this.runs = runs; this.current = current;
    }

    @GetMapping("/{eventId}")
    @Operation(operationId = "getSimulationBunchingGroup", summary = "Get confirmed simulation bus identities in arrival order")
    public SimulationBunchingGroupResponse group(@PathVariable UUID runId, @PathVariable UUID eventId) {
        var alert = runs.bunchingGroup(runId, eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation bunching group not found"));
        var affected = alert.affectedVehicles().stream().map(value -> value.busId()).toList();
        var following = alert.followingVehicle() == null ? null : alert.followingVehicle().busId();
        return new SimulationBunchingGroupResponse(runId, eventId, alert.serviceId(), alert.directionId(),
                alert.stopCode(), affected, following);
    }

    @GetMapping("/{eventId}/current")
    @Operation(operationId = "getCurrentSimulationBunchingGroup",
            summary = "Resolve the latest simulation bunching observations to bus identities",
            description = "Requires the planning peer capability. Returns 409 when current detector evidence is stale, "
                    + "not the newest replay snapshot, insufficient or cannot be resolved uniquely.")
    @ApiResponse(responseCode = "200", description = "Current affected buses in predicted arrival order")
    @ApiResponse(responseCode = "409", description = "Current bunching group cannot be established")
    public CurrentGroupResponse current(@PathVariable UUID runId, @PathVariable UUID eventId) {
        var group = current.find(runId, eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Current simulation bunching group unavailable"));
        return new CurrentGroupResponse(group.demoRunId(), group.bunchingEventId(), group.serviceId(), group.stopCode(),
                group.directionId(), group.rawObservationId(), group.fetchedAt(), group.validUntil(), group.affectedBusReferences(),
                group.adjacentGapSeconds(), group.consecutivePairs());
    }

    public record CurrentGroupResponse(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode,
            int directionId, UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Prediction gaps in seconds between successive affected buses, in the same order; other non-affected buses may lie between them")
            List<Long> adjacentGapSeconds,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Whether each gap connects consecutive buses in the full simulation snapshot")
            List<Boolean> consecutivePairs) {}
}
