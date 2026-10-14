package com.csd.occ.disruption.traffic.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TrafficIncidentPollPort {
    TrafficIncidentPollPage page(Instant afterCompletedAt, UUID afterPollRunId, int limit);

    record TrafficIncidentPollPage(List<TrafficIncidentPoll> polls, boolean hasMore) { }

    record TrafficIncidentPoll(UUID pollRunId, String source, String outcome, boolean complete,
            String classification, UUID demoRunId, Instant startedAt, Instant completedAt,
            List<TrafficIncidentFact> incidents) { }

    record TrafficIncidentFact(UUID id, UUID rawObservationId, String sourceIncidentKey, String incidentType,
            String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt,
            Instant fetchedAt, String classification, UUID demoRunId) { }
}
