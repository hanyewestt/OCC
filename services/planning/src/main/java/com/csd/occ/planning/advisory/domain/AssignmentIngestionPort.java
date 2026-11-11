package com.csd.occ.planning.advisory.domain;

import java.util.UUID;

public interface AssignmentIngestionPort {
    Receipt apply(Command command);

    record Command(UUID operationId, UUID runId, String busId, UUID captainUserId, long expectedVersion,
            String actorSubject) { }
    record Receipt(UUID operationId, UUID runId, String busId, String outcome, UUID previousCaptainUserId,
            UUID captainUserId, long beforeVersion, long afterVersion, String reason) { }
}
