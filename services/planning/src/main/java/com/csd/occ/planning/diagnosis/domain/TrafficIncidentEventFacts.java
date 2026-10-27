package com.csd.occ.planning.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Producer-owned traffic incident facts for one immutable lifecycle transition. */
public record TrafficIncidentEventFacts(UUID eventId, UUID disruptionId, String action,
        String classification, UUID demoRunId, UUID sourceObservationId, Long sourceRevision, UUID pollRunId,
        String source, String sourceIncidentKey, String incidentType, String message,
        BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt, Instant eventAt) {
    public TrafficIncidentEventFacts {
        if (eventId == null || disruptionId == null || sourceObservationId == null || pollRunId == null
                || sourceIncidentKey == null || !sourceIncidentKey.matches("[0-9a-f]{64}")
                || action == null || !Set.of("OPENED", "UPDATED", "CLEARED").contains(action)
                || source == null || source.isBlank() || source.length() > 40
                || incidentType == null || incidentType.isBlank() || incidentType.length() > 60
                || message == null || message.isBlank() || message.length() > 500
                || latitude == null || longitude == null || fetchedAt == null || eventAt == null)
            throw new IllegalArgumentException("Traffic incident event facts are incomplete or outside supported bounds");
        if (sourceRevision != null && sourceRevision < 1)
            throw new IllegalArgumentException("Traffic incident source revision must be positive");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)
            throw new IllegalArgumentException("Traffic incident coordinates are outside valid bounds");
        if (!("OBSERVATION_ONLY".equals(classification) && demoRunId == null)
                && !("SIMULATION".equals(classification) && demoRunId != null))
            throw new IllegalArgumentException("Traffic incident event scope is invalid");
    }

    public TrafficIncidentEventFacts(UUID eventId, UUID disruptionId, String action, String classification,
            UUID demoRunId, UUID sourceObservationId, UUID pollRunId, String source, String sourceIncidentKey,
            String incidentType, String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt,
            Instant fetchedAt, Instant eventAt) {
        this(eventId, disruptionId, action, classification, demoRunId, sourceObservationId, null, pollRunId,
                source, sourceIncidentKey, incidentType, message, latitude, longitude, observedAt, fetchedAt, eventAt);
    }
}
