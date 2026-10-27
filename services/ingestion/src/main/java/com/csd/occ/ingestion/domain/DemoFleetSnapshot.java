package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record DemoFleetSnapshot(UUID demoRunId, String status, List<Vehicle> items, long totalElements,
                                int page, int size) {
    public record Vehicle(String busId, String tripId, String registrationNumber, String serviceId,
                          int directionId, Optional<UUID> captainUserId, String assignmentStatus,
                          Optional<Progress> progress) {}
    public record Progress(String stopCode, int stopSequence, Instant observedAt,
                           int assumedSpeedKmh, List<Integer> allowedSpeedReductionsKmh,
                           List<Integer> allowedDurationsSeconds) {}
}
