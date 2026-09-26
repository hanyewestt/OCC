package com.csd.occ.operations.api;

import com.csd.occ.operations.domain.LiveMapPort;
import com.csd.occ.operations.domain.MapSettings;
import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import com.csd.occ.operations.api.dto.LiveMapResponse;
import com.csd.occ.operations.api.dto.MapSettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/map")
public class LiveMapController {
    private final LiveMapPort observations;
    private final MapSettings settings;
    private final DemoFleetPagePolicy pages;
    public LiveMapController(LiveMapPort observations, MapSettings settings, DemoFleetPagePolicy pages) {
        this.observations = observations; this.settings = settings; this.pages = pages;
    }
    @GetMapping("/settings")
    @Operation(operationId = "getMapSettings", summary = "Read public map display settings")
    public MapSettingsResponse settings() {
        return new MapSettingsResponse(settings.tileUrl(), settings.attributionText(), settings.attributionUrl(), settings.refreshIntervalMs(), settings.cognitoEnabled(), settings.rawObservationsDefault());
    }
    @GetMapping("/observed-buses")
    @Operation(operationId = "getLiveMapObservations", summary = "Read unverified DataMall positions",
            description = "Anonymous read-only observation feed. A marker is an arrival observation, not a verified unique bus. No Cognito token or demo run is needed.")
    @ApiResponse(responseCode = "200", description = "Current observation page",
            content = @Content(schema = @Schema(implementation = LiveMapResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid paging parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Ingestion unavailable",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public LiveMapResponse observations(@RequestParam(required = false) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        int limit = size == null ? pages.defaultSize() : size;
        if (limit > pages.maxSize()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size exceeds configured maximum");
        var result = observations.observations(serviceId, page, limit);
        return new LiveMapResponse(result.items().stream().map(item -> new LiveMapResponse.Observation(
                item.observationId(), item.stopCode(), item.serviceId(), item.directionId(), item.visitOrder(),
                item.estimatedArrivalAt(), item.latitude(), item.longitude(), item.fetchedAt(), item.stale(),
                "OBSERVATION_ONLY", "UNVERIFIED")).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
    }
}
