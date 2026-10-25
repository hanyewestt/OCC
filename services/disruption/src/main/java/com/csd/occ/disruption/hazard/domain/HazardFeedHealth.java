package com.csd.occ.disruption.hazard.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "hazard_feed_health", schema = "disruption")
public class HazardFeedHealth {
    @Id private UUID id;
    private String kind;
    private String source;
    private String scope;
    private String classification;
    private UUID demoRunId;
    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private String lastOutcome;
    private boolean lastComplete;
    private long staleAfterSeconds;
    protected HazardFeedHealth() {}
    public HazardFeedHealth(HazardPollPort.Poll poll, java.time.Duration staleAfter) {
        id = UUID.randomUUID(); kind = poll.scope(); source = poll.source(); scope = poll.scope();
        classification = poll.classification(); demoRunId = poll.demoRunId();
        advance(poll, staleAfter);
    }
    public boolean matches(HazardPollPort.Poll poll) {
        return kind.equals(poll.scope()) && source.equals(poll.source()) && scope.equals(poll.scope())
                && classification.equals(poll.classification()) && java.util.Objects.equals(demoRunId, poll.demoRunId());
    }
    public void advance(HazardPollPort.Poll poll, java.time.Duration staleAfter) {
        lastAttemptAt = poll.completedAt(); lastOutcome = poll.outcome(); lastComplete = poll.complete();
        staleAfterSeconds = staleAfter.toSeconds();
        if (poll.complete() && "SUCCESS".equals(poll.outcome())) lastSuccessAt = poll.completedAt();
    }
    public HazardCheckpoint.FeedHealth health(Instant now) {
        String status = "SUCCESS".equals(lastOutcome) && lastComplete ? "HEALTHY"
                : "PARTIAL".equals(lastOutcome) || "SUCCESS".equals(lastOutcome) ? "DEGRADED" : "FAILED";
        if (lastSuccessAt != null && !now.isBefore(lastSuccessAt.plusSeconds(staleAfterSeconds))) status = "STALE";
        return new HazardCheckpoint.FeedHealth(kind, source, scope, classification, demoRunId, status, lastAttemptAt, lastSuccessAt);
    }
}
