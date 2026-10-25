package com.csd.occ.disruption.hazard.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HazardTransitPort {
    double associationRadiusKm();
    Fleet fleet(UUID runId, int page);
    Route route(String serviceId);
    Optional<Station> station(String code);
    record Fleet(UUID demoRunId, String status, List<Bus> items, long totalElements, int page, int size) {}
    record Bus(String busId, String tripId, String registrationNumber, String serviceId, int directionId,
               UUID captainUserId, String assignmentStatus, Progress progress) {}
    record Progress(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                    List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds) {}
    record Route(String serviceId, List<Operator> operators) {}
    record Operator(String operator, List<Direction> directions) {}
    record Direction(int directionId, List<Stop> stops) {}
    record Stop(String stopCode, String roadName, String description, BigDecimal latitude,
                BigDecimal longitude, int stopSequence) {}
    record Station(String code, String name, BigDecimal latitude, BigDecimal longitude, String versionChecksum) {}
}
