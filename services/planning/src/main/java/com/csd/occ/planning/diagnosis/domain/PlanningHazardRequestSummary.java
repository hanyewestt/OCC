package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.UUID;

public record PlanningHazardRequestSummary(UUID requestId, UUID eventId, UUID disruptionId, String eventType,
        DiagnosisRequestStatus status, String failureCode, Instant createdAt) {}
