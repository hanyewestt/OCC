package com.csd.occ.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncidentEvidence(String evidenceId, UUID incidentId, String incidentType, String message,
                               BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt,
                               String classification, UUID demoRunId) {
}
