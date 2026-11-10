package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.SimulationBunchingGroupResponse;
import com.csd.occ.ingestion.domain.DemoRunService;
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

    public SimulationBunchingGroupController(DemoRunService runs) { this.runs = runs; }

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
}
