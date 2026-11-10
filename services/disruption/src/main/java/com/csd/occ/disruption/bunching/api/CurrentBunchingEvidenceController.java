package com.csd.occ.disruption.bunching.api;

import com.csd.occ.disruption.bunching.domain.CurrentBunchingEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/disruption/demo-runs/{runId}/bunching-groups")
public class CurrentBunchingEvidenceController {
    private final CurrentBunchingEvidenceService evidence;

    public CurrentBunchingEvidenceController(CurrentBunchingEvidenceService evidence) { this.evidence = evidence; }

    @GetMapping("/{episodeId}/current-evidence")
    @Operation(operationId = "getCurrentSimulationBunchingEvidence",
            summary = "Get the latest detector evidence for an active simulation bunching episode",
            description = "Requires the ingestion peer capability. Freshness only describes the source snapshot; "
                    + "consumers must also require BUNCHING and resolve each observation to a unique bus.")
    @ApiResponse(responseCode = "200", description = "Latest evaluated evidence, which may be stale or insufficient")
    @ApiResponse(responseCode = "404", description = "Active episode or retained evidence not found")
    public EvidenceResponse find(@PathVariable UUID runId, @PathVariable UUID episodeId) {
        var result = evidence.find(runId, episodeId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Current simulation bunching evidence unavailable"));
        return new EvidenceResponse(result.demoRunId(), result.bunchingEventId(), result.serviceId(), result.stopCode(),
                result.directionId(), result.state(), result.rawObservationId(), result.fetchedAt(), result.validUntil(),
                result.fresh(), result.affectedObservationIds());
    }

    @Schema(description = "Latest detector evaluation for one active simulation episode; observation IDs are not bus IDs")
    public record EvidenceResponse(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
            @Schema(description = "Detector state", allowableValues = {"BUNCHING", "NORMAL", "INSUFFICIENT_EVIDENCE"}) String state,
            UUID rawObservationId, Instant fetchedAt, Instant validUntil,
            @Schema(description = "True when the snapshot is not future-dated and the current time is before validUntil") boolean fresh,
            @Schema(description = "Affected arrival observation IDs in detector order; empty when no pair is bunched") List<UUID> affectedObservationIds) {}
}
