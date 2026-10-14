package com.csd.occ.disruption.traffic.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TrafficIncidentSnapshot(UUID sourceObservationId, UUID observationPollRunId, String sourceIncidentKey,
        String incidentType, String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt,
        Instant fetchedAt) {
    public static TrafficIncidentSnapshot of(TrafficIncidentPollPort.TrafficIncidentFact fact, UUID pollRunId) {
        return new TrafficIncidentSnapshot(fact.id(), pollRunId, fact.sourceIncidentKey(), fact.incidentType(), fact.message(),
                fact.latitude(), fact.longitude(), fact.observedAt(), fact.fetchedAt());
    }

    public static TrafficIncidentSnapshot of(TrafficIncidentState state) {
        return new TrafficIncidentSnapshot(state.sourceObservationId(), state.lastPollRunId(), state.sourceIncidentKey(),
                state.incidentType(), state.message(), state.latitude(), state.longitude(), state.observedAt(), state.fetchedAt());
    }
}
