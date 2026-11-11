package com.csd.occ.planning.domain.audit;

import java.util.UUID;
import java.time.Instant;
import java.util.Map;

public interface ApplicationAuditCapturePort {
    void recordAi(String eventType, String outcome, UUID disruptionId, UUID runId, String busId,
            String purpose, String model, String failureCategory, String input, String output);

    void recordAi(String eventType, String outcome, UUID disruptionId, UUID runId, String busId,
            String purpose, String model, String failureCategory, String input, String output,
            java.util.Map<String, Object> metrics);

    void recordRecommendationAttempt(UUID invocationId, Instant occurredAt, UUID recommendationId, UUID requestId,
            String outcome, Map<String, Object> details);
}
