package com.csd.occ.disruption.traffic.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TrafficIncidentSnapshot(UUID sourceObservationId, long sourceRevision, UUID observationPollRunId, String sourceIncidentKey,
        String incidentType, String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt,
        Instant fetchedAt) {
    public static TrafficIncidentSnapshot of(TrafficIncidentState state) {
        return new TrafficIncidentSnapshot(state.sourceObservationId(), state.sourceRevision(), state.lastPollRunId(), state.sourceIncidentKey(),
                state.incidentType(), state.message(), state.latitude(), state.longitude(), state.observedAt(), state.fetchedAt());
    }
}
