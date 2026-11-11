package com.csd.occ.planning.domain.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits safe processing evidence independently so a caller rollback cannot erase a failure record. */
@Service
public class ApplicationAuditCaptureService implements ApplicationAuditCapturePort {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public ApplicationAuditCaptureService(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void recordAi(String eventType, String outcome, UUID disruptionId, UUID runId, String busId,
            String purpose, String model, String failureCategory, String input, String output) {
        recordAi(eventType, outcome, disruptionId, runId, busId, purpose, model, failureCategory, input, output, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void recordAi(String eventType, String outcome, UUID disruptionId, UUID runId, String busId,
            String purpose, String model, String failureCategory, String input, String output,
            Map<String, Object> metrics) {
        UUID id = UUID.randomUUID(); Instant now = clock.instant();
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("purpose", purpose); details.put("model", model);
        details.put("promptVersion", purpose + "-prompt-v1");
        details.put("schemaVersion", purpose + "-schema-v1");
        details.put("attempt", 1); details.put("latencyMillis", null);
        details.put("promptTokens", null); details.put("completionTokens", null); details.put("totalTokens", null);
        details.put("failureCategory", failureCategory); details.put("input", input); details.put("output", output);
        details.putAll(metrics);
        events.save(new ApplicationAuditEvent(id, now, now, "AI_PROCESSING", eventType, outcome, null, "SYSTEM",
                "PLANNING", disruptionId == null ? "unknown" : disruptionId.toString(), runId, busId, null,
                null, "AI processing evidence recorded", details, "ai-evidence:" + id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void recordRecommendationAttempt(UUID invocationId, Instant occurredAt, UUID recommendationId, UUID requestId,
            String outcome, Map<String, Object> details) {
        String sourceKey = "model-invocation:" + invocationId;
        if (events.existsBySourceKey(sourceKey)) return;
        Instant recordedAt = clock.instant();
        events.save(new ApplicationAuditEvent(invocationId, occurredAt, recordedAt, "AI_PROCESSING",
                "AI_RECOMMENDATION_ATTEMPT", outcome, null, "SYSTEM", "RECOMMENDATION",
                recommendationId.toString(), null, requestId.toString(), "Recommendation model attempt recorded",
                details, sourceKey));
    }
}
