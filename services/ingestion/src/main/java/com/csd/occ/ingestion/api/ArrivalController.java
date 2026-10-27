package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.ArrivalResponse;
import com.csd.occ.ingestion.api.dto.ArrivalPageResponse;
import com.csd.occ.ingestion.api.dto.FeedStatusResponse;
import com.csd.occ.ingestion.api.dto.PageResponse;
import com.csd.occ.ingestion.api.dto.PollRunResponse;
import com.csd.occ.ingestion.api.dto.ReferenceTopologyResponse;
import com.csd.occ.ingestion.domain.BusArrivalObservation;
import com.csd.occ.ingestion.domain.CurrentBusArrivalView;
import com.csd.occ.ingestion.domain.FeedStatus;
import com.csd.occ.ingestion.domain.IngestionQueryService;
import com.csd.occ.ingestion.domain.PollRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ingestion")
public class ArrivalController {
    private final IngestionQueryService queries;

    public ArrivalController(IngestionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/arrivals")
    @Operation(operationId = "listCurrentArrivals", summary = "List latest arrival predictions by stop or service")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Current arrivals returned",
            content = @Content(schema = @Schema(implementation = ArrivalPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pagination or sort parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public PageResponse<ArrivalResponse> currentArrivals(@RequestParam(required = false) String stopCode,
            @RequestParam(required = false) String serviceId,
            @Parameter(description = "Trimmed, case-insensitive literal substring of the versioned stop name")
            @RequestParam(required = false) String stopName,
            @Parameter(description = "Sort by arrival time or stop name",
                    schema = @Schema(allowableValues = {"arrival", "stopName"}, defaultValue = "arrival"))
            @RequestParam(defaultValue = "arrival") String sortBy,
            @Parameter(description = "Sort direction", schema = @Schema(allowableValues = {"asc", "desc"}, defaultValue = "asc"))
            @RequestParam(defaultValue = "asc") String sortDirection,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        if (!List.of("arrival", "stopName").contains(sortBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sortBy must be arrival or stopName");
        }
        if (!List.of("asc", "desc").contains(sortDirection)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sortDirection must be asc or desc");
        }
        return page(queries.currentArrivals(stopCode, serviceId, stopName, sortBy, sortDirection,
                PageRequest.of(page, size)), this::currentResponse);
    }

    @GetMapping("/arrivals/history")
    @Operation(operationId = "listArrivalHistory", summary = "List retained normalized arrival observations")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Arrival history returned",
            content = @Content(schema = @Schema(implementation = ArrivalPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters or time range",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public PageResponse<ArrivalResponse> arrivalHistory(@RequestParam(required = false) String stopCode,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to");
        }
        return page(queries.arrivalHistory(stopCode, serviceId, from, to, PageRequest.of(page, size)),
                this::historyResponse);
    }

    @GetMapping("/feed-status")
    @Operation(operationId = "listFeedStatuses", summary = "List polling health for each configured stop")
    public List<FeedStatusResponse> feedStatuses() {
        Instant now = queries.now();
        return queries.feedStatuses().stream().map(status -> feedStatusResponse(status, now)).toList();
    }

    @GetMapping("/poll-runs")
    @Operation(operationId = "listPollRuns", summary = "List polling audit runs")
    @ApiResponses(@ApiResponse(responseCode = "400", description = "Invalid pagination parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))))
    public PageResponse<PollRunResponse> pollRuns(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return page(queries.pollRuns(PageRequest.of(page, size)), this::pollRunResponse);
    }

    @GetMapping("/reference-topology")
    @Operation(operationId = "activeReferenceTopology", summary = "Get the active DataMall topology version")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No active reference topology",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))))
    public ReferenceTopologyResponse activeReferenceTopology() {
        return queries.activeReferenceVersion().map(version -> new ReferenceTopologyResponse(version.id(), version.checksum()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active reference topology"));
    }

    private ArrivalResponse currentResponse(CurrentBusArrivalView value) {
        return new ArrivalResponse(value.getId(), value.getStopCode(), value.getStopName(), value.getServiceId(), value.getOperator(),
                value.getDirectionId(), value.getVisitOrder(), value.getVisitNumber(), value.getEstimatedArrivalAt(), value.getOriginCode(),
                value.getDestinationCode(), value.getMonitored(), value.getLatitude(), value.getLongitude(),
                value.getLoadCategory(), value.getFeature(), value.getVehicleType(), value.getFetchedAt(),
                value.getAgeSeconds(), value.getReferenceVersionId());
    }

    private ArrivalResponse historyResponse(BusArrivalObservation value) {
        return new ArrivalResponse(value.getId(), value.getStopCode(), null, value.getServiceId(), value.getOperator(),
                value.getDirectionId(), value.getVisitOrder(), value.getVisitNumber(), value.getEstimatedArrivalAt(), value.getOriginCode(),
                value.getDestinationCode(), value.getMonitored(), value.getLatitude(), value.getLongitude(),
                value.getLoadCategory(), value.getFeature(), value.getVehicleType(), value.getFetchedAt(), null,
                value.getReferenceVersionId());
    }

    private FeedStatusResponse feedStatusResponse(FeedStatus value, Instant now) {
        boolean stale = value.getLastSuccessAt() == null || value.getLastSuccessAt()
                .plusSeconds(value.getStaleAfterSeconds()).isBefore(now);
        return new FeedStatusResponse(value.getSource(), value.getScopeKey(), value.getStatus(), value.getLastAttemptAt(),
                value.getLastSuccessAt(), value.getConsecutiveFailures(), value.getStaleAfterSeconds(), value.getMessage(),
                value.getUpdatedAt(), stale);
    }

    private PollRunResponse pollRunResponse(PollRun value) {
        return new PollRunResponse(value.getId(), value.getSource(), value.getScopeKey(), value.getStartedAt(),
                value.getCompletedAt(), value.getOutcome(), value.getHttpStatus(), value.getRecordsReceived(),
                value.getErrorCode(), value.getErrorMessage());
    }

    private <T, R> PageResponse<R> page(Page<T> source, java.util.function.Function<T, R> mapper) {
        return new PageResponse<>(source.getContent().stream().map(mapper).toList(), source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
}
