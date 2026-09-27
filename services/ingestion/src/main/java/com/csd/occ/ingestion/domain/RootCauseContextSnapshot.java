package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public record RootCauseContextSnapshot(String classification, UUID demoRunId, UUID referenceVersionId,
                                       List<RootCauseArrival> arrivals, List<RootCauseIncident> incidents,
                                       List<String> missingFields) {
    public RootCauseContextSnapshot {
        arrivals = List.copyOf(arrivals); incidents = List.copyOf(incidents); missingFields = List.copyOf(missingFields);
    }
    public record RootCauseArrival(String evidenceId, UUID observationId, UUID rawObservationId, UUID pollRunId,
            String serviceId, String stopCode, Integer directionId, java.time.Instant estimatedArrivalAt,
            java.time.Instant fetchedAt, java.math.BigDecimal latitude, java.math.BigDecimal longitude,
            String loadCategory, String vehicleType, String classification, UUID demoRunId, UUID referenceVersionId) { }
    public record RootCauseIncident(String evidenceId, UUID incidentId, String incidentType, String message,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, java.time.Instant observedAt,
            java.time.Instant fetchedAt, String classification, UUID demoRunId) { }
}
