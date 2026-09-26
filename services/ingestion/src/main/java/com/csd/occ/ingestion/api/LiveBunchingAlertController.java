package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.LiveBunchingAlertPageResponse;
import com.csd.occ.ingestion.api.dto.LiveBunchingAlertResponse;
import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.LiveBunchingAlert;
import com.csd.occ.ingestion.domain.LiveBunchingAlertPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "occ.ingestion.demo.enabled", havingValue = "true")
@RequestMapping(value = "/api/v1/live-bunching-alerts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Live bunching alerts")
public class LiveBunchingAlertController {
    private final BunchingControlPort bunching;

    public LiveBunchingAlertController(BunchingControlPort bunching) { this.bunching = bunching; }

    @GetMapping
    @Operation(operationId = "listCurrentLiveBunchingAlertsFromIngestion",
            summary = "List current live bunching detections for the demo page")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Current live alerts returned, possibly empty",
            content = @Content(schema = @Schema(implementation = LiveBunchingAlertPageResponse.class))),
            @ApiResponse(responseCode = "503", description = "Bunching alert service is unavailable",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))})
    public LiveBunchingAlertPageResponse currentOpen(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum alerts per page (1-200)") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        LiveBunchingAlertPage alerts = bunching.currentLiveAlerts(page, size);
        return new LiveBunchingAlertPageResponse(alerts.items().stream().map(this::response).toList(), alerts.page(),
                alerts.size(), alerts.totalItems(), alerts.totalPages());
    }

    private LiveBunchingAlertResponse response(LiveBunchingAlert alert) {
        return new LiveBunchingAlertResponse(alert.eventId(), alert.alertId(), alert.action(), alert.classification(),
                alert.serviceId(), alert.stopCode(), alert.directionId(), alert.detectedAt(), alert.fetchedAt(),
                alert.rawObservationId(), alert.pollRunId(), alert.evidence());
    }
}
