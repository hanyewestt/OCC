package com.csd.occ.disruption.domain.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditRecorder {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public ApplicationAuditRecorder(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void disruptionTransition(UUID eventId, UUID disruptionId, UUID runId, Instant occurredAt,
            String kind, String action, String status) {
        record(occurredAt, "OPERATIONS", "DISRUPTION_TRANSITION", "SUCCESS", "DISRUPTION", disruptionId.toString(),
                runId, "Disruption " + kind + " " + action.toLowerCase(java.util.Locale.ROOT),
                Map.of("kind", kind, "action", action, "status", status), "disruption:" + eventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void feedPoll(UUID pollId, String feed, UUID runId, Instant occurredAt, String outcome, boolean complete) {
        String safeOutcome = switch (outcome == null ? "" : outcome) {
            case "SUCCESS", "PARTIAL", "FAILED", "FAILURE" -> outcome;
            default -> "UNKNOWN";
        };
        String result = "SUCCESS".equals(safeOutcome) && complete ? "SUCCESS" : "FAILURE";
        record(occurredAt, "OPERATIONS", "FEED_POLL_COMPLETED", result, "FEED", feed, runId,
                "Feed poll completed with " + safeOutcome,
                Map.of("outcome", safeOutcome, "complete", complete), "feed-poll:" + pollId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void feedHealthChange(UUID pollId, String feed, UUID runId, Instant occurredAt,
            String previous, String current) {
        record(occurredAt, "OPERATIONS", "FEED_HEALTH_CHANGED", "SUCCESS", "FEED", feed, runId,
                "Feed health changed from " + previous + " to " + current,
                Map.of("previous", previous, "current", current), "feed-health:" + pollId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void planningSubmission(UUID outboxId, int attempt, UUID runId, Instant occurredAt,
            String outcome, Integer status, String code) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("attempt", attempt);
        details.put("httpStatus", status);
        String safeOutcome = switch (outcome) {
            case "DELIVERED", "RETRY", "FAILED" -> outcome;
            default -> "FAILED";
        };
        String eventOutcome = switch (safeOutcome) {
            case "DELIVERED" -> "SUCCESS";
            case "RETRY" -> "RETRY";
            default -> "FAILURE";
        };
        details.put("failureCode", code != null && code.matches("[A-Z0-9_]{1,80}") ? code : null);
        record(occurredAt, "OPERATIONS", "PLANNING_SUBMISSION_" + safeOutcome, eventOutcome,
                "PLANNING_OUTBOX", outboxId.toString(), runId, "Planning submission " + safeOutcome.toLowerCase(java.util.Locale.ROOT),
                details, "planning-submission:" + outboxId + ":" + safeOutcome + ":" + attempt);
    }

    private void record(Instant occurredAt, String category, String eventType, String outcome, String entityType,
            String entityId, UUID runId, String summary, Map<String, Object> details, String sourceKey) {
        events.save(new ApplicationAuditEvent(occurredAt, clock.instant(), category, eventType, outcome,
                null, "SYSTEM", entityType, entityId, runId, summary, details, sourceKey));
    }
}
