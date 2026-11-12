package com.csd.occ.planning.advisory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdvisoryContextPort {
    Hazard hazard(UUID disruptionId);
    Fleet fleet(UUID runId);
    Route route(String serviceId);
    Limits limits();
    BunchingGroup bunchingGroup(UUID demoRunId, UUID bunchingEventId);
    CurrentBunchingGroup currentBunchingGroup(UUID demoRunId, UUID bunchingEventId);
    /** Empty while new simulation predictions are awaiting detector evaluation. */
    Optional<CurrentBunchingGroup> refreshBunchingGroup(UUID demoRunId, UUID bunchingEventId);
    record BunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, int directionId, String stopCode,
                         List<String> affectedBusReferences, String followingBusReference) {}
    record CurrentBunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                                UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
                                List<Long> adjacentGapSeconds, List<Boolean> consecutivePairs) {
        public boolean hasReliableGap(int index) {
            return adjacentGapSeconds != null && consecutivePairs != null
                    && adjacentGapSeconds.size() == affectedBusReferences.size() - 1
                    && consecutivePairs.size() == adjacentGapSeconds.size()
                    && Boolean.TRUE.equals(consecutivePairs.get(index))
                    && adjacentGapSeconds.get(index) != null && adjacentGapSeconds.get(index) >= 0;
        }

        public boolean isFreshAt(Instant now) {
            return rawObservationId != null && fetchedAt != null && validUntil != null
                    && !fetchedAt.isAfter(now) && now.isBefore(validUntil);
        }

        public CurrentBunchingGroup(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences) {
            this(demoRunId, bunchingEventId, serviceId, stopCode, directionId, rawObservationId, fetchedAt, validUntil,
                    affectedBusReferences, List.of(), List.of());
        }
    }
    record Limits(int maxSpeedReductionKmh, int maxDurationSeconds, int maxSubsequentStops,
                  Duration acknowledgementWindow, Duration retention) {}
    record Hazard(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                  UUID demoRunId, UUID sourceObservationId, Long sourceRevision, Instant updatedAt,
                  HazardEventFacts.Fact facts, List<Exposure> exposures, List<String> limitations) {
        public Hazard(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardEventFacts.Fact facts,
                List<Exposure> exposures, List<String> limitations) {
            this(disruptionId, kind, status, inactiveReason, classification, demoRunId, sourceObservationId, null,
                    updatedAt, facts, exposures, limitations);
        }

        public boolean hasSameEvidenceAs(Hazard latest) {
            return sourceRevision != null
                    ? sourceRevision.equals(latest.sourceRevision)
                    : java.util.Objects.equals(sourceObservationId, latest.sourceObservationId);
        }
    }
    record Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                    int busProgressSequence, int stopSequence, String basis, String limitation) {
        public Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                int stopSequence, String basis, String limitation) {
            this(busId, serviceId, directionId, stopCode, stopName, stopSequence, stopSequence, basis, limitation);
        }
    }
    record Fleet(UUID demoRunId, String status, List<Bus> items, long totalElements) {}
    record Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
               UUID captainUserId, String assignmentStatus, Progress progress, long assignmentRevision) {
        public Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
                UUID captainUserId, String assignmentStatus, Progress progress) {
            this(busId, tripId, registrationNumber, serviceId, directionId, captainUserId, assignmentStatus, progress, 0);
        }
    }
    record Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                    List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds,
                    Instant estimatedArrivalAt, Instant freshUntil, Instant boardingUntil) {
        public Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds,
                Instant estimatedArrivalAt, Instant freshUntil) {
            this(stopCode, stopSequence, observedAt, assumedSpeedKmh, allowedSpeedReductionsKmh,
                    allowedDurationsSeconds, estimatedArrivalAt, freshUntil, null);
        }

        public Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds) {
            this(stopCode, stopSequence, observedAt, assumedSpeedKmh, allowedSpeedReductionsKmh,
                    allowedDurationsSeconds, null, null);
        }

        public boolean isApproachingAt(Instant now) {
            return isFreshAt(now) && estimatedArrivalAt != null && now.isBefore(estimatedArrivalAt);
        }

        public boolean isBoardingAt(Stop stop, CurrentBunchingGroup group, Instant now) {
            return stop.stopCode().equals(stopCode) && stop.stopSequence() == stopSequence
                    && group.fetchedAt().equals(observedAt) && group.isFreshAt(now) && isFreshAt(now)
                    && estimatedArrivalAt != null && !now.isBefore(estimatedArrivalAt)
                    && boardingUntil != null && now.isBefore(boardingUntil);
        }

        private boolean isFreshAt(Instant now) {
            return observedAt != null && !observedAt.isAfter(now) && freshUntil != null && now.isBefore(freshUntil);
        }
    }
    record Route(String serviceId, List<Operator> operators) {
        public java.util.Optional<Stop> upcomingStop(int directionId, String stopCode, int progressSequence) {
            var stops = operators.stream().flatMap(operator -> operator.directions().stream())
                    .filter(direction -> direction.directionId() == directionId).flatMap(direction -> direction.stops().stream())
                    .filter(stop -> stop.stopCode().equals(stopCode) && stop.stopSequence() >= progressSequence)
                    .toList();
            return stops.size() == 1 ? java.util.Optional.of(stops.getFirst()) : java.util.Optional.empty();
        }
    }
    record Operator(String operator, String category, List<Direction> directions) {}
    record Direction(int directionId, List<Stop> stops) {}
    record Stop(String stopCode, String description, int stopSequence) {}
}
