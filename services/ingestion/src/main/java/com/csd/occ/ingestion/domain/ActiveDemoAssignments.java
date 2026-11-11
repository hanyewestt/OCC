package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public record ActiveDemoAssignments(UUID runId, String status, List<Bus> items) {
    public ActiveDemoAssignments { items = List.copyOf(items); }
    public record Bus(String busId, String tripId, String registrationNumber, String serviceId,
            int directionId, UUID captainUserId, long assignmentRevision,
            PendingAssignmentOperationsPort.Pending pendingOperation) { }
}
