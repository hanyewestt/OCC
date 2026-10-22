package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One predicted bus arrival used for pacing. Simulation buses carry their fictional registration;
 * live Bus Arrival predictions have no vehicle identity, so {@code vehicleReference} is empty.
 */
public record PacingArrival(String classification, String stopCode, String serviceId, int directionId,
                            Instant estimatedArrivalAt, Instant fetchedAt, Optional<String> vehicleReference) {
    public PacingArrival {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(stopCode, "stopCode");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(estimatedArrivalAt, "estimatedArrivalAt");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(vehicleReference, "vehicleReference");
    }
}
