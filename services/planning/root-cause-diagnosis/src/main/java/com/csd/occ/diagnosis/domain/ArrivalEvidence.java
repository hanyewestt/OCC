package com.csd.occ.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ArrivalEvidence(String evidenceId, UUID observationId, UUID rawObservationId, UUID pollRunId,
                              String serviceId, String stopCode, Integer directionId, Instant estimatedArrivalAt,
                              Instant fetchedAt, BigDecimal latitude, BigDecimal longitude, String loadCategory,
                              String vehicleType, String classification, UUID demoRunId, UUID referenceVersionId) {
    public ArrivalEvidence {
        bounded(evidenceId, 200, "evidenceId");
        bounded(serviceId, 20, "serviceId");
        bounded(stopCode, 12, "stopCode");
        bounded(loadCategory, 20, "loadCategory");
        bounded(vehicleType, 10, "vehicleType");
    }

    private static void bounded(String value, int max, String field) {
        if (value != null && value.length() > max) throw new EvidenceLimitExceededException(field);
    }
}
