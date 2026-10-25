package com.csd.occ.planning.advisory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdvisoryContextPort {
    Hazard hazard(UUID disruptionId);
    Fleet fleet(UUID runId);
    Route route(String serviceId);
    Limits limits();
    record Limits(int maxSpeedReductionKmh, int maxDurationSeconds, int maxSubsequentStops,
                  Duration acknowledgementWindow, Duration retention) {}
    record Hazard(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                  UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardEventFacts.Fact facts,
                  List<Exposure> exposures, List<String> limitations) {}
    record Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                    int stopSequence, String basis, String limitation) {}
    record Fleet(UUID demoRunId, String status, List<Bus> items, long totalElements) {}
    record Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
               UUID captainUserId, String assignmentStatus, Progress progress) {}
    record Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                    List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds) {}
    record Route(String serviceId, List<Operator> operators) {}
    record Operator(String operator, String category, List<Direction> directions) {}
    record Direction(int directionId, List<Stop> stops) {}
    record Stop(String stopCode, String description, int stopSequence) {}
}
