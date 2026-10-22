package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.PacingGapResponse;
import com.csd.occ.ingestion.api.dto.PacingMetricResponse;
import com.csd.occ.ingestion.api.dto.PacingSnapshotResponse;
import com.csd.occ.ingestion.domain.PacingConfigurationPort;
import com.csd.occ.ingestion.domain.PacingGap;
import com.csd.occ.ingestion.domain.PacingMetric;
import com.csd.occ.ingestion.domain.PacingService;
import com.csd.occ.ingestion.domain.PacingSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/pacing-snapshots")
@Tag(name = "Pacing", description = "Gap to the bus ahead and behind for bus captain On Board Units. "
        + "Readable by ADMIN, CONTROLLER and CAPTAIN.")
public class PacingSnapshotController {
    static final String EVENT_NAME = "pacing";

    private final PacingService pacing;
    private final PacingConfigurationPort configuration;

    public PacingSnapshotController(PacingService pacing, PacingConfigurationPort configuration) {
        this.pacing = pacing;
        this.configuration = configuration;
    }

    @GetMapping(value = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getLatestPacingSnapshot", summary = "Get the most recent pacing snapshot",
            description = "Returns the latest calculated gaps once. Readable by ADMIN, CONTROLLER and CAPTAIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest snapshot, possibly with no items",
                    content = @Content(schema = @Schema(implementation = PacingSnapshotResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role is not ADMIN, CONTROLLER or CAPTAIN",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "No snapshot has been calculated yet",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))})
    public PacingSnapshotResponse latest(
            @Parameter(description = "Only include this bus service") @RequestParam(required = false)
            @Size(max = 20) String serviceId,
            @Parameter(description = "Only include this vehicle reference") @RequestParam(required = false)
            @Size(max = 80) String vehicleReference) {
        return response(pacing.latest().filtered(selection(serviceId, vehicleReference)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "streamPacingSnapshots", summary = "Stream pacing snapshots as Server-Sent Events",
            description = "Pushes the current snapshot immediately, then one `pacing` event per calculation cycle "
                    + "(every 10 seconds by default). Each event's data is a PacingSnapshotResponse. The server "
                    + "closes the stream after the configured timeout; clients reconnect. Readable by ADMIN, "
                    + "CONTROLLER and CAPTAIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event stream of pacing snapshots",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = PacingSnapshotResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role is not ADMIN, CONTROLLER or CAPTAIN",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "The stream has reached its subscriber limit",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<SseEmitter> stream(
            @Parameter(description = "Only include this bus service") @RequestParam(required = false)
            @Size(max = 20) String serviceId,
            @Parameter(description = "Only include this vehicle reference") @RequestParam(required = false)
            @Size(max = 80) String vehicleReference) {
        Predicate<PacingMetric> include = selection(serviceId, vehicleReference);
        SseEmitter emitter = new SseEmitter(configuration.streamTimeout().toMillis());
        PacingService.Subscription subscription = pacing.subscribe(snapshot -> send(emitter, snapshot.filtered(include)));
        emitter.onCompletion(subscription::cancel);
        emitter.onTimeout(subscription::cancel);
        emitter.onError(error -> subscription.cancel());
        // Nginx must forward each event immediately instead of buffering the response.
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    private void send(SseEmitter emitter, PacingSnapshot snapshot) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).id(snapshot.calculatedAt().toString())
                    .data(response(snapshot), MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Predicate<PacingMetric> selection(String serviceId, String vehicleReference) {
        return metric -> (serviceId == null || serviceId.equals(metric.serviceId()))
                && (vehicleReference == null || vehicleReference.equals(metric.vehicleReference()));
    }

    private static PacingSnapshotResponse response(PacingSnapshot snapshot) {
        return new PacingSnapshotResponse(snapshot.calculatedAt(),
                snapshot.metrics().stream().map(PacingSnapshotController::response).toList());
    }

    private static PacingMetricResponse response(PacingMetric metric) {
        return new PacingMetricResponse(metric.vehicleReference(), metric.classification(), metric.stopCode(),
                metric.serviceId(), metric.directionId(), metric.targetHeadway().toSeconds(),
                metric.ahead().map(PacingSnapshotController::response).orElse(null),
                metric.behind().map(PacingSnapshotController::response).orElse(null), metric.observedAt());
    }

    private static PacingGapResponse response(PacingGap gap) {
        return new PacingGapResponse(gap.headway().toSeconds(), gap.state().name());
    }
}
