package com.csd.occ.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ArrivalEvidence(String evidenceId, UUID observationId, UUID rawObservationId, UUID pollRunId,
                              String serviceId, String stopCode, Integer directionId, Instant estimatedArrivalAt,
                              Instant fetchedAt, BigDecimal latitude, BigDecimal longitude, String loadCategory,
                              String vehicleType, String classification, UUID demoRunId, UUID referenceVersionId) {
}
