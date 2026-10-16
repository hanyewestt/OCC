package com.csd.occ.disruption.bunching.domain;

import java.util.UUID;

public record DiagnosisOutboxClaim(UUID id, UUID eventId, int attemptNumber, String payloadJson,
                                   String payloadFingerprint, String schemaVersion) {
    public DiagnosisOutboxClaim(UUID id, UUID eventId, int attemptNumber) {
        this(id, eventId, attemptNumber, null, null, null);
    }
}
