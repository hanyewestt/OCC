package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.HazardPollFeedService;
import com.csd.occ.ingestion.domain.HazardFeedPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HazardPollPageResponse(List<PollResponse> polls, boolean hasMore) {
    public static HazardPollPageResponse from(HazardPollFeedService.Page page) {
        return new HazardPollPageResponse(page.polls().stream().map(p -> new PollResponse(p.pollRunId(), p.source(),
                p.scope(), p.outcome(), p.complete(), p.classification(), p.demoRunId(), p.completedAt(),
                p.observations().stream().map(o -> new ObservationResponse(o.id(), o.rawObservationId(), o.facts(), o.fetchedAt())).toList())).toList(), page.hasMore());
    }
    public record PollResponse(UUID pollRunId, String source, String scope, String outcome, boolean complete,
            String classification, UUID demoRunId, Instant completedAt, List<ObservationResponse> observations) {}
    public record ObservationResponse(UUID id, UUID rawObservationId, HazardFeedPort.Fact facts, Instant fetchedAt) {}
}
