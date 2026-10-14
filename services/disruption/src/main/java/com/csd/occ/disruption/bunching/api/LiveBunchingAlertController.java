package com.csd.occ.disruption.bunching.api;

import com.csd.occ.disruption.bunching.api.dto.LiveBunchingAlertPageResponse;
import com.csd.occ.disruption.bunching.api.dto.LiveBunchingAlertResponse;
import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.LiveBunchingAlertService;
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
@RequestMapping(value = {"/api/v1/disruption/bunching-alerts", "/internal/v1/disruption/live-bunching-alerts"},
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Live bunching alerts")
public class LiveBunchingAlertController {
    private final LiveBunchingAlertService alerts;

    public LiveBunchingAlertController(LiveBunchingAlertService alerts) { this.alerts = alerts; }

    @GetMapping
    @Operation(operationId = "listCurrentLiveBunchingAlerts",
            summary = "List currently open bunching detections from the live feed")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Current live alerts returned, possibly empty",
            content = @Content(schema = @Schema(implementation = LiveBunchingAlertPageResponse.class))))
    public LiveBunchingAlertPageResponse currentOpen(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum alerts per page (1-200)") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Page<AlertEvent> events = alerts.currentOpen(page, size);
        return new LiveBunchingAlertPageResponse(events.getContent().stream().map(this::response).toList(),
                events.getNumber(), events.getSize(), events.getTotalElements(), events.getTotalPages());
    }

    private LiveBunchingAlertResponse response(AlertEvent event) {
        return new LiveBunchingAlertResponse(event.id(), event.alertId(), event.action(), event.classification(),
                event.serviceId(), event.stopCode(), event.directionId(), event.triggeredAt(), event.fetchedAt(),
                event.rawObservationId(), event.pollRunId(), event.evidence());
    }
}
