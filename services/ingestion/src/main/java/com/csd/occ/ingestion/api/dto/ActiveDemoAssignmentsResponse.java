package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.ActiveDemoAssignments;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Current demo bus assignments merged with durable pending Planning operations")
public record ActiveDemoAssignmentsResponse(UUID runId, String status, List<Bus> items) {
    public static ActiveDemoAssignmentsResponse from(ActiveDemoAssignments value) {
        return new ActiveDemoAssignmentsResponse(value.runId(), value.status(), value.items().stream()
                .map(bus -> new Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), bus.serviceId(),
                        bus.directionId(), bus.captainUserId(), bus.assignmentRevision(),
                        bus.pendingOperation() == null ? null : new PendingOperation(
                                bus.pendingOperation().operationId(), bus.pendingOperation().status())))
                .toList());
    }
    public record Bus(String busId, String tripId, String registrationNumber, String serviceId,
            int directionId, UUID captainUserId, long assignmentRevision, PendingOperation pendingOperation) { }
    public record PendingOperation(UUID operationId, String status) { }
}
