package com.csd.occ.disruption.hazard.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "hazard_checkpoint", schema = "disruption")
public class HazardCheckpoint {
    @Id private String kind;
    private Instant completedAt;
    private UUID pollRunId;
    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private String lastOutcome;
    private boolean lastComplete;
    private long staleAfterSeconds;
    protected HazardCheckpoint() {}
    public String kind() { return kind; }
    public Instant completedAt() { return completedAt; }
    public UUID pollRunId() { return pollRunId; }
    public boolean isBefore(HazardPollPort.Poll poll) {
        return completedAt == null || poll.completedAt().isAfter(completedAt)
                || poll.completedAt().equals(completedAt) && compareUuid(poll.pollRunId(), pollRunId) > 0;
    }
    private static int compareUuid(UUID left, UUID right) {
        int mostSignificant = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSignificant != 0 ? mostSignificant
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
    public void advance(HazardPollPort.Poll poll, java.time.Duration staleAfter) {
        completedAt = poll.completedAt(); pollRunId = poll.pollRunId(); lastAttemptAt = poll.completedAt();
        lastOutcome = poll.outcome(); lastComplete = poll.complete();
        staleAfterSeconds = staleAfter.toSeconds();
        if (poll.complete() && "SUCCESS".equals(poll.outcome())) lastSuccessAt = poll.completedAt();
    }
    public FeedHealth health(Instant now) {
        String status = lastAttemptAt == null ? "UNKNOWN" : "SUCCESS".equals(lastOutcome) && lastComplete
                ? "HEALTHY" : "PARTIAL".equals(lastOutcome) || "SUCCESS".equals(lastOutcome) ? "DEGRADED" : "FAILED";
        if (lastSuccessAt != null && !now.isBefore(lastSuccessAt.plusSeconds(staleAfterSeconds))) status = "STALE";
        return new FeedHealth(kind, status, lastAttemptAt, lastSuccessAt);
    }
    public record FeedHealth(String kind, String status, Instant lastAttemptAt, Instant lastSuccessAt) {}
}
