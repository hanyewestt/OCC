package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.UUID;

public record PlanningHazardHistorySummary(UUID requestId, UUID eventId, UUID disruptionId, String eventType,
        String title, DiagnosisRequestStatus status, String failureCode, Instant createdAt, Instant completedAt) {}
