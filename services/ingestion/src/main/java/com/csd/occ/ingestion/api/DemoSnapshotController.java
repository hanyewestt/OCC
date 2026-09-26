package com.csd.occ.ingestion.api;
import com.csd.occ.ingestion.api.dto.BunchingSnapshotPageResponse;
import com.csd.occ.ingestion.api.dto.BunchingSnapshotResponse;
import com.csd.occ.ingestion.domain.BunchingFeedService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
@ConditionalOnProperty(name = "occ.ingestion.demo.enabled", havingValue = "true")
@RequestMapping("/internal/v1/demo-runs/{demoRunId}/arrival-snapshots")
public class DemoSnapshotController {
    private final BunchingFeedService feed;
    public DemoSnapshotController(BunchingFeedService feed) { this.feed = feed; }
    @GetMapping public BunchingSnapshotPageResponse page(@PathVariable UUID demoRunId,
            @RequestParam(defaultValue = "0") long afterSequence, @RequestParam(defaultValue = "100") int size) {
        var page = feed.demoPage(demoRunId, afterSequence, size);
        return new BunchingSnapshotPageResponse(page.items().stream().map(BunchingSnapshotControllerMapper::response).toList(), page.nextSequence());
    }
    @GetMapping("/{rawObservationId}") public BunchingSnapshotResponse snapshot(@PathVariable UUID demoRunId, @PathVariable UUID rawObservationId) {
        return feed.demoSnapshot(demoRunId, rawObservationId).map(BunchingSnapshotControllerMapper::response)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo snapshot not found"));
    }
}
