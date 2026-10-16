package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

public class TrafficIncidentPollFeedService {
    private final PollRunRepository pollRuns;
    private final TrafficIncidentObservationRepository incidents;
    private final int defaultPageSize;
    private final int maxPageSize;

    public TrafficIncidentPollFeedService(PollRunRepository pollRuns,
            TrafficIncidentObservationRepository incidents, int defaultPageSize, int maxPageSize) {
        this.pollRuns = pollRuns;
        this.incidents = incidents;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
    }

    @Transactional(readOnly = true)
    public TrafficIncidentPollPage page(Instant afterCompletedAt, UUID afterPollRunId, Integer requestedLimit) {
        if ((afterCompletedAt == null) != (afterPollRunId == null))
            throw new IllegalArgumentException("afterCompletedAt and afterPollRunId must be provided together");
        int limit = requestedLimit == null ? defaultPageSize : requestedLimit;
        if (limit < 1 || limit > maxPageSize)
            throw new IllegalArgumentException("limit must be between 1 and " + maxPageSize);
        var pageRequest = PageRequest.of(0, limit + 1);
        List<PollRun> runs = afterCompletedAt == null
                ? pollRuns.findCompletedTrafficPolls(pageRequest)
                : pollRuns.findCompletedTrafficPollsAfter(afterCompletedAt, afterPollRunId, pageRequest);
        boolean hasMore = runs.size() > limit;
        List<PollRun> selectedRuns = runs.stream().limit(limit).toList();
        Map<UUID, List<TrafficIncidentObservation>> byPollRun = incidents.findByPollRunIdInOrderByFetchedAtAsc(
                        selectedRuns.stream().map(PollRun::getId).toList()).stream()
                .collect(Collectors.groupingBy(TrafficIncidentObservation::pollRunId));
        List<TrafficIncidentPoll> page = selectedRuns.stream()
                .map(run -> new TrafficIncidentPoll(run.getId(), run.getSource(), run.getOutcome(),
                        "SUCCESS".equals(run.getOutcome()), run.getClassification(), run.getDemoRunId(),
                        run.getStartedAt(), run.getCompletedAt(), byPollRun.getOrDefault(run.getId(), List.of())
                                .stream().map(incident -> new TrafficIncidentFact(incident.id(),
                                        incident.rawObservationId(), incident.sourceIncidentKey(), incident.incidentType(),
                                        incident.message(), incident.latitude(), incident.longitude(), incident.observedAt(),
                                        incident.fetchedAt(), incident.classification(), incident.demoRunId())).toList()))
                .toList();
        return new TrafficIncidentPollPage(page, hasMore);
    }

    public record TrafficIncidentPollPage(List<TrafficIncidentPoll> polls, boolean hasMore) { }
    public record TrafficIncidentPoll(UUID pollRunId, String source, String outcome, boolean complete,
            String classification, UUID demoRunId, Instant startedAt, Instant completedAt,
            List<TrafficIncidentFact> incidents) { }
    public record TrafficIncidentFact(UUID id, UUID rawObservationId, String sourceIncidentKey, String incidentType,
            String message, java.math.BigDecimal latitude, java.math.BigDecimal longitude, Instant observedAt,
            Instant fetchedAt, String classification, UUID demoRunId) { }
}
