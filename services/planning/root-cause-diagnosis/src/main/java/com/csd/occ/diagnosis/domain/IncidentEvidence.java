package com.csd.occ.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncidentEvidence(String evidenceId, UUID incidentId, String incidentType, String message,
                               BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt,
                               String classification, UUID demoRunId) {
    public IncidentEvidence {
        bounded(evidenceId, 200, "evidenceId");
        bounded(classification, 30, "classification");
        bounded(incidentType, 60, "incidentType");
        bounded(message, 500, "message");
    }

    private static void bounded(String value, int max, String field) {
        if (value != null && value.length() > max) throw new EvidenceLimitExceededException(field);
    }
}
