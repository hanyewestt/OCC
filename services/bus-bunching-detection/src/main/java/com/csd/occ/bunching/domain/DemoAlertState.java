package com.csd.occ.bunching.domain;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "demo_alert_state", schema = "bunching")
public class DemoAlertState {
    protected DemoAlertState() {}
    @EmbeddedId private DemoAlertStateId id;
    private Instant lastFetchedAt;
    private UUID activeAlertId;
    private UUID leadingObservationId;
    private UUID followingObservationId;
    private Integer pendingHeadwaySeconds;
    private Instant pendingDetectedAt;
    private UUID pendingLeadingObservationId;
    private UUID pendingFollowingObservationId;
    private UUID pendingRawObservationId;
    private UUID pendingPollRunId;
    public Instant lastFetchedAt() { return lastFetchedAt; }
    public UUID activeAlertId() { return activeAlertId; }
    public UUID leadingObservationId() { return leadingObservationId; }
    public UUID followingObservationId() { return followingObservationId; }
    public Integer pendingHeadwaySeconds() { return pendingHeadwaySeconds; }
    public Instant pendingDetectedAt() { return pendingDetectedAt; }
    public UUID pendingLeadingObservationId() { return pendingLeadingObservationId; }
    public UUID pendingFollowingObservationId() { return pendingFollowingObservationId; }
    public UUID pendingRawObservationId() { return pendingRawObservationId; }
    public UUID pendingPollRunId() { return pendingPollRunId; }
    public void update(Instant fetchedAt, UUID activeAlertId, UUID leadingObservationId, UUID followingObservationId) {
        lastFetchedAt = fetchedAt; this.activeAlertId = activeAlertId;
        this.leadingObservationId = leadingObservationId; this.followingObservationId = followingObservationId;
    }
    public void recordCandidate(Instant fetchedAt, int headwaySeconds, UUID leadingObservationId,
                                UUID followingObservationId, UUID rawObservationId, UUID pollRunId) {
        lastFetchedAt = fetchedAt; pendingHeadwaySeconds = headwaySeconds; pendingDetectedAt = fetchedAt;
        pendingLeadingObservationId = leadingObservationId; pendingFollowingObservationId = followingObservationId;
        pendingRawObservationId = rawObservationId; pendingPollRunId = pollRunId;
    }
    public void clearCandidate() {
        pendingHeadwaySeconds = null; pendingDetectedAt = null; pendingLeadingObservationId = null;
        pendingFollowingObservationId = null; pendingRawObservationId = null; pendingPollRunId = null;
    }
}
