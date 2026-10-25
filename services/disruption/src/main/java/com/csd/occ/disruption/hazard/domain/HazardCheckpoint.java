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
        completedAt = poll.completedAt(); pollRunId = poll.pollRunId();
    }
    public record FeedHealth(String kind, String source, String scope, String classification, UUID demoRunId,
                             String status, Instant lastAttemptAt, Instant lastSuccessAt) {}
}
