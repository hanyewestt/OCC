package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.BunchingArrivalResponse;
import com.csd.occ.ingestion.api.dto.BunchingSnapshotPageResponse;
import com.csd.occ.ingestion.api.dto.BunchingSnapshotResponse;
import com.csd.occ.ingestion.domain.BunchingFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/internal/v1/arrival-snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bunching ingestion")
public class BunchingSnapshotController {
    private final BunchingFeedService feed;

    public BunchingSnapshotController(BunchingFeedService feed) {
        this.feed = feed;
    }

    @GetMapping
    @Operation(operationId = "listBunchingArrivalSnapshots", summary = "List normalized snapshots for bunching")
    public BunchingSnapshotPageResponse snapshots(@RequestParam(defaultValue = "0") @Min(0) long afterSequence,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int size) {
        BunchingFeedService.FeedPage page = feed.page(afterSequence, size);
        return new BunchingSnapshotPageResponse(page.items().stream().map(this::response).toList(), page.nextSequence());
    }

    @GetMapping("/{rawObservationId}")
    @Operation(operationId = "getBunchingArrivalSnapshot", summary = "Get one normalized snapshot for retry")
    public BunchingSnapshotResponse snapshot(@PathVariable UUID rawObservationId) {
        return feed.snapshot(rawObservationId).map(this::response)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arrival snapshot not found"));
    }

    private BunchingSnapshotResponse response(BunchingFeedService.Snapshot value) {
        return new BunchingSnapshotResponse(value.sequence(), value.rawObservationId(), value.pollRunId(),
                value.sourceKey(), value.fetchedAt(), value.normalizationState(), value.arrivals().stream()
                .map(arrival -> new BunchingArrivalResponse(arrival.observationId(), arrival.referenceVersionId(),
                        arrival.stopCode(), arrival.serviceId(), arrival.directionId(), arrival.visitOrder(),
                        arrival.estimatedArrivalAt(), arrival.originCode(), arrival.destinationCode(), arrival.latitude(),
                        arrival.longitude())).toList());
    }
}
