package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.domain.TrafficIncidentPollFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/internal/v1/traffic-incident-polls", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Traffic incident ingestion")
public class TrafficIncidentPollController {
    private final TrafficIncidentPollFeedService feed;

    public TrafficIncidentPollController(TrafficIncidentPollFeedService feed) { this.feed = feed; }

    @GetMapping
    @Operation(operationId = "listCompletedTrafficIncidentPolls", summary = "Read a bounded page of completed traffic polls")
    public TrafficIncidentPollPageResponse list(
            @Parameter(description = "Cursor timestamp from the last poll returned") @RequestParam(required = false) Instant afterCompletedAt,
            @Parameter(description = "Poll-run ID paired with afterCompletedAt") @RequestParam(required = false) UUID afterPollRunId,
            @RequestParam(required = false) Integer limit) {
        var result = feed.page(afterCompletedAt, afterPollRunId, limit);
        return new TrafficIncidentPollPageResponse(result.polls().stream().map(poll ->
                new TrafficIncidentPollResponse(poll.pollRunId(), poll.source(), poll.outcome(), poll.complete(),
                        poll.classification(), poll.demoRunId(), poll.startedAt(), poll.completedAt(),
                        poll.incidents().stream().map(value -> new TrafficIncidentResponse(value.id(),
                                value.rawObservationId(), value.sourceIncidentKey(), value.incidentType(), value.message(),
                                value.latitude(), value.longitude(), value.observedAt(), value.fetchedAt(),
                                value.classification(), value.demoRunId())).toList())).toList(), result.hasMore());
    }

    public record TrafficIncidentPollPageResponse(List<TrafficIncidentPollResponse> polls, boolean hasMore) { }
    public record TrafficIncidentPollResponse(UUID pollRunId, String source, String outcome, boolean complete,
            String classification, UUID demoRunId, Instant startedAt, Instant completedAt,
            List<TrafficIncidentResponse> incidents) { }
    public record TrafficIncidentResponse(UUID id, UUID rawObservationId, String sourceIncidentKey, String incidentType,
            String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt,
            String classification, UUID demoRunId) { }
}
