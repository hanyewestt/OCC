package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.domain.HazardKind;
import com.csd.occ.ingestion.domain.HazardPollFeedService;
import com.csd.occ.ingestion.api.dto.HazardPollPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class HazardPollController {
    private final HazardPollFeedService feed;
    public HazardPollController(HazardPollFeedService feed) { this.feed = feed; }
    @GetMapping("/internal/v1/hazard-polls")
    @Operation(operationId = "listHazardPolls", summary = "Consume completed hazard polls",
            description = "Disruption peer only. kind selects an independent feed; paired afterCompletedAt/afterPollRunId cursor. "
                    + "limit defaults to 20, range 1–100. Traffic uses its existing endpoint. Incomplete polls never establish absence.")
    @ApiResponse(responseCode = "200", description = "Polls ordered by completion time and UUID")
    @ApiResponse(responseCode = "400", description = "Invalid kind, cursor or limit")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Disruption peer required")
    public HazardPollPageResponse page(@RequestParam HazardKind kind,
            @RequestParam(required = false) Instant afterCompletedAt, @RequestParam(required = false) UUID afterPollRunId,
            @RequestParam(defaultValue = "20") int limit) {
        return HazardPollPageResponse.from(feed.page(kind, afterCompletedAt, afterPollRunId, limit));
    }
}
