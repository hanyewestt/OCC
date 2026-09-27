package com.csd.occ.ingestion.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RootCauseContextResponse(String classification, UUID demoRunId, UUID referenceVersionId,
                                       List<ArrivalEvidenceResponse> arrivals,
                                       List<IncidentEvidenceResponse> incidents, List<String> missingFields) {
    public RootCauseContextResponse {
        arrivals = List.copyOf(arrivals); incidents = List.copyOf(incidents); missingFields = List.copyOf(missingFields);
    }
    public record ArrivalEvidenceResponse(String evidenceId, UUID observationId, UUID rawObservationId, UUID pollRunId,
            String serviceId, String stopCode, Integer directionId, Instant estimatedArrivalAt, Instant fetchedAt,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, String loadCategory, String vehicleType,
            String classification, UUID demoRunId, UUID referenceVersionId) { }
    public record IncidentEvidenceResponse(String evidenceId, UUID incidentId, String incidentType, String message,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, Instant observedAt, Instant fetchedAt,
            String classification, UUID demoRunId) { }
}
