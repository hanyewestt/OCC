package com.csd.occ.disruption.bunching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A single successful ingestion snapshot; never combine rows from different polls. */
public record ArrivalSnapshot(
        UUID rawObservationId,
        UUID pollRunId,
        Optional<UUID> sourcePollCycleId,
        Optional<UUID> referenceVersionId,
        Instant fetchedAt,
        List<Arrival> arrivals,
        String classification,
        UUID demoRunId) {

    public ArrivalSnapshot {
        Objects.requireNonNull(rawObservationId);
        Objects.requireNonNull(pollRunId);
        Objects.requireNonNull(sourcePollCycleId);
        Objects.requireNonNull(referenceVersionId);
        Objects.requireNonNull(fetchedAt);
        Objects.requireNonNull(classification);
        if (!("OBSERVATION_ONLY".equals(classification) && demoRunId == null)
                && !("SIMULATION".equals(classification) && demoRunId != null))
            throw new IllegalArgumentException("Snapshot classification and demo run scope do not match");
        arrivals = List.copyOf(arrivals);
    }

    public ArrivalSnapshot(UUID rawObservationId, UUID pollRunId, Optional<UUID> referenceVersionId,
                           Instant fetchedAt, List<Arrival> arrivals) {
        this(rawObservationId, pollRunId, Optional.empty(), referenceVersionId, fetchedAt, arrivals,
                "OBSERVATION_ONLY", null);
    }

    public ArrivalSnapshot(UUID rawObservationId, UUID pollRunId, UUID referenceVersionId,
                           Instant fetchedAt, List<Arrival> arrivals) {
        this(rawObservationId, pollRunId, Optional.of(referenceVersionId), fetchedAt, arrivals);
    }

    public ArrivalSnapshot(UUID rawObservationId, UUID pollRunId, Optional<UUID> referenceVersionId,
                           Instant fetchedAt, List<Arrival> arrivals, String classification, UUID demoRunId) {
        this(rawObservationId, pollRunId, Optional.empty(), referenceVersionId, fetchedAt, arrivals,
                classification, demoRunId);
    }

    /** visitOrder identifies an arrival slot in this snapshot, not a vehicle. */
    public record Arrival(
            UUID observationId,
            String serviceId,
            String stopCode,
            Optional<Integer> directionId,
            int visitOrder,
            Optional<Instant> estimatedArrivalAt,
            String operator,
            Integer visitNumber,
            String originCode,
            String destinationCode,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer routeStopSequence) {

        public Arrival {
            Objects.requireNonNull(observationId);
            Objects.requireNonNull(serviceId);
            Objects.requireNonNull(stopCode);
            Objects.requireNonNull(directionId);
            Objects.requireNonNull(estimatedArrivalAt);
            if (serviceId.isBlank() || stopCode.isBlank()) {
                throw new IllegalArgumentException("Service and stop must not be blank");
            }
            if (visitOrder < 1 || visitOrder > 100) {
                throw new IllegalArgumentException("Visit order must be between 1 and 100");
            }
            if (directionId.isPresent() && directionId.get() <= 0) {
                throw new IllegalArgumentException("Mapped direction must be positive");
            }
            if (visitNumber != null && visitNumber <= 0) {
                throw new IllegalArgumentException("Visit number must be positive when present");
            }
            if (routeStopSequence != null && routeStopSequence <= 0) {
                throw new IllegalArgumentException("Route stop sequence must be positive when present");
            }
        }

        public Arrival(UUID observationId, String serviceId, String stopCode, Optional<Integer> directionId,
                       int visitOrder, Optional<Instant> estimatedArrivalAt) {
            this(observationId, serviceId, stopCode, directionId, visitOrder, estimatedArrivalAt,
                    null, null, null, null, null, null, null);
        }
    }
}
