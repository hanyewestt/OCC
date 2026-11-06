package com.csd.occ.planning.diagnosis.api.dto;

import java.util.UUID;

public record TriggerDiagnosisRequest(
        UUID eventId,
        UUID requestId) {
}
