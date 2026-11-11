package com.csd.occ.planning.api.dto;

import com.csd.occ.planning.advisory.domain.AssignmentOperation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AssignmentOperationResponse(UUID operationId, String status, UUID runId, String busId,
        UUID captainUserId, long expectedVersion, Instant createdAt, Instant updatedAt, Map<String, Object> result) {
    public static AssignmentOperationResponse from(AssignmentOperation operation) {
        return new AssignmentOperationResponse(operation.id(), operation.status(), operation.demoRunId(), operation.busId(),
                operation.captainUserId(), operation.expectedVersion(), operation.createdAt(), operation.updatedAt(), operation.result());
    }
}
