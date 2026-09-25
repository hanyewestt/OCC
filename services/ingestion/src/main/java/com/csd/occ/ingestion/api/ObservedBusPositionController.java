package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.ObservedBusPositionResponse;
import com.csd.occ.ingestion.api.dto.ObservedBusPositionPageResponse;
import com.csd.occ.ingestion.domain.CurrentBusArrivalView;
import com.csd.occ.ingestion.domain.IngestionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/map/observed-buses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ingestion")
public class ObservedBusPositionController {
    private final IngestionQueryService queries;

    public ObservedBusPositionController(IngestionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(operationId = "listObservedBusPositions",
            summary = "List usable DataMall arrival positions for a map",
            description = "Returns source-monitored arrival slots with usable coordinates from each target's latest "
                    + "successful poll. Each item is an observation, not a verified vehicle identity. Stale items "
                    + "must not be presented as current bus positions. No plate, driver, or inferred track is returned.")
    @ApiResponse(responseCode = "200", description = "Paginated unverified arrival positions",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ObservedBusPositionPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid pagination parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ObservedBusPositionPageResponse positions(@RequestParam(required = false) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Instant asOf = queries.now();
        Page<CurrentBusArrivalView> result = queries.mapPositions(serviceId, PageRequest.of(page, size));
        return new ObservedBusPositionPageResponse(result.getContent().stream().map(value -> response(value, asOf)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private ObservedBusPositionResponse response(CurrentBusArrivalView value, Instant asOf) {
        return new ObservedBusPositionResponse(value.getId(), value.getRawObservationId(), value.getStopCode(),
                value.getServiceId(), value.getDirectionId(), value.getVisitOrder(), value.getEstimatedArrivalAt(),
                value.getLatitude(), value.getLongitude(), value.getFetchedAt(), queries.isStale(value.getFetchedAt(), asOf),
                "OBSERVATION_ONLY", "UNVERIFIED");
    }
}
