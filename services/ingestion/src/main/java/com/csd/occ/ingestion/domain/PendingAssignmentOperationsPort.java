package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

/** Narrow Planning projection used to make uncertain admin assignments visible after reload. */
public interface PendingAssignmentOperationsPort {
    List<Pending> findPending(UUID runId);
    record Pending(String busId, UUID operationId, String status) { }
}
