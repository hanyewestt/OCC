package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Immutable idempotent assignment operation receipt")
public record DemoAssignmentOperationResponse(UUID operationId, UUID runId, String busId,
        Outcome outcome, UUID previousCaptainUserId, UUID captainUserId,
        long beforeVersion, long afterVersion, String reason) {
    public enum Outcome { APPLIED, NO_OP, REJECTED }
}
