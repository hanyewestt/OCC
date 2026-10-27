package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HazardPollFeedService {
    private final PollRunRepository polls;
    private final HazardObservationRepository observations;
    public HazardPollFeedService(PollRunRepository polls, HazardObservationRepository observations) {
        this.polls = polls; this.observations = observations;
    }
    @Transactional(readOnly = true)
    public Page page(HazardKind kind, Instant afterAt, UUID afterId, int limit) {
        if (kind == HazardKind.TRAFFIC_INCIDENT) throw new IllegalArgumentException("Use traffic-incident-polls");
        if ((afterAt == null) != (afterId == null) || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Paired cursor and limit 1–100 required");
        var request = PageRequest.of(0, limit + 1);
        var found = afterAt == null ? polls.findBySourceInAndCompletedAtIsNotNullOrderByCompletedAtAscIdAsc(
                List.of(kind.name() + "_DATAMALL", kind.name() + "_DEMO"), request)
                : polls.findHazardPolls(kind.name() + "_DATAMALL", kind.name() + "_DEMO", afterAt, afterId, request);
        return new Page(found.stream().limit(limit).map(p -> new Poll(p.getId(), p.getSource(), p.getScopeKey(),
                p.getOutcome(), "SUCCESS".equals(p.getOutcome()), p.getClassification(), p.getDemoRunId(),
                p.getCompletedAt(), observations.findByPollRunIdOrderById(p.getId()).stream().map(o ->
                new Observation(o.id(), o.rawObservationId(), o.facts(), o.fetchedAt())).toList())).toList(), found.size() > limit);
    }
    public record Page(List<Poll> polls, boolean hasMore) {}
    public record Poll(UUID pollRunId, String source, String scope, String outcome, boolean complete,
                       String classification, UUID demoRunId, Instant completedAt, List<Observation> observations) {}
    public record Observation(UUID id, UUID rawObservationId, HazardFeedPort.Fact facts, Instant fetchedAt) {}
}
