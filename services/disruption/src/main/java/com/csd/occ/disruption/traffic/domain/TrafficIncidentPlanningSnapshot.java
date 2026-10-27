package com.csd.occ.disruption.traffic.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable typed facts for one traffic-incident lifecycle transition submitted to Planning. */
public record TrafficIncidentPlanningSnapshot(UUID eventId, UUID disruptionId, String action,
        String classification, UUID demoRunId, UUID sourceObservationId, long sourceRevision, UUID pollRunId,
        String source, String sourceIncidentKey, String incidentType, String message,
        BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt, Instant eventAt) {
    public TrafficIncidentPlanningSnapshot {
        if (eventId == null || disruptionId == null || sourceObservationId == null || sourceRevision < 1 || pollRunId == null
                || sourceIncidentKey == null || !sourceIncidentKey.matches("[0-9a-f]{64}")
                || action == null || !java.util.Set.of("OPENED", "UPDATED", "CLEARED").contains(action)
                || source == null || source.isBlank() || source.length() > 40
                || incidentType == null || incidentType.isBlank() || incidentType.length() > 60
                || message == null || message.isBlank() || message.length() > 500
                || latitude == null || longitude == null || fetchedAt == null || eventAt == null)
            throw new IllegalArgumentException("Traffic incident planning snapshot is incomplete");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)
            throw new IllegalArgumentException("Traffic incident coordinates are outside valid bounds");
        if (!("OBSERVATION_ONLY".equals(classification) && demoRunId == null)
                && !("SIMULATION".equals(classification) && demoRunId != null))
            throw new IllegalArgumentException("Traffic incident planning snapshot scope is invalid");
    }

    public static TrafficIncidentPlanningSnapshot of(UUID eventId, UUID disruptionId, String action,
            TrafficIncidentPollPort.TrafficIncidentPoll poll, TrafficIncidentSnapshot snapshot) {
        return new TrafficIncidentPlanningSnapshot(eventId, disruptionId, action, poll.classification(),
                poll.demoRunId(), snapshot.sourceObservationId(), snapshot.sourceRevision(), poll.pollRunId(), poll.source(),
                snapshot.sourceIncidentKey(), snapshot.incidentType(), snapshot.message(), snapshot.latitude(),
                snapshot.longitude(), snapshot.observedAt(), snapshot.fetchedAt(), poll.completedAt());
    }
}
