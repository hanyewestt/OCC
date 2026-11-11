package com.csd.occ.planning.api.dto;

import com.csd.occ.planning.advisory.domain.AssignmentOperationCoordinator;
import java.util.List;

public record PendingAssignmentOperationsResponse(List<AssignmentOperationCoordinator.PendingAssignment> items) {
    public PendingAssignmentOperationsResponse { items = List.copyOf(items); }
}
