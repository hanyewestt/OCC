package com.csd.occ.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits terminal poll, feed health and their audit snapshots together after provider I/O completes. */
@Service
public class PollCompletionAuditService {
    private final PollRunRepository polls;
    private final FeedStatusRepository feeds;
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public PollCompletionAuditService(PollRunRepository polls, FeedStatusRepository feeds,
            ApplicationAuditEventRepository events, Clock clock) {
        this.polls = polls;
        this.feeds = feeds;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void complete(PollRun run, FeedStatus feedStatus, String previousHealth) {
        polls.save(run);
        if (feedStatus != null) feeds.save(feedStatus);
        var details = new LinkedHashMap<String, Object>();
        details.put("source", run.getSource());
        details.put("scope", run.getScopeKey());
        details.put("pollOutcome", run.getOutcome());
        details.put("recordsReceived", run.getRecordsReceived());
        details.put("classification", run.getClassification());
        details.put("httpStatus", run.getHttpStatus());
        details.put("failureCode", safeCode(run.getErrorCode()));
        details.put("latencyMillis", run.getStartedAt() == null || run.getCompletedAt() == null ? null
                : Math.max(0, Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis()));
        var pollSourceKey = "ingestion-poll:" + run.getId();
        events.save(event(run, "FEED_POLL_COMPLETED", pollOutcome(run.getOutcome()), "POLL_RUN",
                run.getId().toString(), "Feed poll completed", details, pollSourceKey));

        if (feedStatus != null) {
            String currentHealth = feedStatus.getStatus();
            String before = previousHealth == null ? "UNKNOWN" : previousHealth;
            if (!java.util.Objects.equals(before, currentHealth)) {
                events.save(event(run, "FEED_HEALTH_CHANGED", "SUCCESS", "FEED",
                        feedStatus.getSource() + ":" + feedStatus.getScopeKey(), "Feed health changed",
                        Map.of("source", feedStatus.getSource(), "scope", feedStatus.getScopeKey(),
                                "previous", before, "current", currentHealth),
                        "ingestion-feed-health:" + run.getId()));
            }
        }
    }

    private ApplicationAuditEvent event(PollRun run, String type, String outcome, String entityType,
            String entityId, String summary, Map<String, ?> details, String sourceKey) {
        var eventId = UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
        return new ApplicationAuditEvent(eventId, run.getCompletedAt(), clock.instant(), "OPERATIONS", type,
                outcome, "INGESTION", null, "SYSTEM", entityType, entityId, run.getDemoRunId(), null,
                null, run.getRoutePositionPollCycleId() == null ? null : run.getRoutePositionPollCycleId().toString(),
                summary, details, sourceKey);
    }

    private static String pollOutcome(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "SUCCESS" -> "SUCCESS";
            case "PARTIAL" -> "PARTIAL";
            case "FAILED" -> "FAILURE";
            default -> "UNKNOWN";
        };
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,32}") ? value : null;
    }
}
