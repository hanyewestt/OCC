package com.csd.occ.planning.diagnosis.api.dto;

import java.util.UUID;

public record TriggerDiagnosisRequest(
        UUID eventId,
        UUID requestId) {
    public TriggerDiagnosisRequest {
        if (eventId == null && requestId == null)
            throw new IllegalArgumentException("Either eventId or requestId must be provided");
    }
}
