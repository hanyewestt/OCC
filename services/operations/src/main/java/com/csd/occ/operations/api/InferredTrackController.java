package com.csd.occ.operations.api;

import com.csd.occ.operations.api.dto.InferredTrackPageResponse;
import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import com.csd.occ.operations.domain.TrackInferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InferredTrackController {
    private final TrackInferenceService inference;
    private final DemoFleetPagePolicy pages;
    public InferredTrackController(TrackInferenceService inference, DemoFleetPagePolicy pages) { this.inference=inference; this.pages=pages; }
    @GetMapping("/api/v1/map/inferred-tracks")
    @Operation(operationId = "getInferredVehicleTracks", summary = "Read current inferred bus tracks",
            description = "Temporary identity hypotheses from DataMall observations. Confidence is a heuristic score, not a probability. Stale and expired tracks are omitted. Tracks cannot receive directives.")
    @ApiResponse(responseCode = "200", description = "Current inferred track page", content = @Content(schema = @Schema(implementation = InferredTrackPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid paging", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public InferredTrackPageResponse tracks(@RequestParam(required = false) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(required = false) @Min(1) Integer size) {
        int limit = size == null ? pages.defaultSize() : size;
        if (limit>pages.maxSize()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size exceeds configured maximum");
        var all=inference.current(serviceId);
        long from=(long)page*limit;
        var selection=from>=all.size() ? List.<com.csd.occ.operations.domain.InferredVehicleTrack>of()
                : all.subList((int)from, (int)Math.min(from+limit, all.size()));
        return new InferredTrackPageResponse(selection.stream().map(t -> new InferredTrackPageResponse.Track(t.getId(),
                t.getServiceId()+" · Track "+t.getId().toString().substring(0,8), t.getServiceId(), t.getDirectionId(), t.getOperator(),
                t.getLatitude(), t.getLongitude(), t.getProgressMeters(), t.getFirstSeenAt(), t.getLastSeenAt(), t.getStatus(),
                t.getConfidence(), t.getConfirmations(), inference.evidence(t.getId()), "OBSERVATION_ONLY", "INFERRED", false)).toList(),
                page,limit,all.size(),(int)Math.ceil((double)all.size()/limit));
    }
}
