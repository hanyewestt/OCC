package com.csd.occ.bunching.api;

import com.csd.occ.bunching.domain.AlertEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/demo-runs")
@Tag(name = "Demo runs (internal)")
public class DemoAlertController {
    private final AlertEventRepository alerts;

    public DemoAlertController(AlertEventRepository alerts) { this.alerts = alerts; }

    @GetMapping("/{demoRunId}/alerts")
    @Operation(operationId = "listSimulationBunchingAlerts",
            summary = "List simulation-only alert events for one demo run")
    @ApiResponse(responseCode = "200", description = "Simulation alerts returned, possibly an empty list")
    public AlertPage alerts(@PathVariable UUID demoRunId) {
        return new AlertPage(alerts.findByDemoRunIdAndClassificationOrderByTriggeredAtDesc(demoRunId, "SIMULATION").stream()
                .map(event -> new AlertResponse(event.id(), event.alertId(), event.action(), event.serviceId(), event.stopCode(), event.directionId(),
                        event.triggeredAt(), event.evidence(), event.classification(),
                        event.leadingObservationId(), event.followingObservationId())).toList());
    }

    @Schema(description = "Simulation alert events belonging only to the requested demo run")
    public record AlertPage(List<AlertResponse> alerts) {
        public AlertPage { alerts = List.copyOf(alerts); }
    }

    @Schema(description = "One bunching transition with IDs linking its fictional leading and following buses")
    public record AlertResponse(UUID eventId, UUID bunchingEventId, String action, String serviceId, String stopCode, int directionId, Instant detectedAt,
                                String evidence, String classification, UUID leadingObservationId,
                                UUID followingObservationId) {}
}
