package com.csd.occ.disruption.bunching.domain;

import java.util.UUID;

public record DiagnosisOutboxClaim(UUID id, UUID eventId, int attemptNumber) { }
