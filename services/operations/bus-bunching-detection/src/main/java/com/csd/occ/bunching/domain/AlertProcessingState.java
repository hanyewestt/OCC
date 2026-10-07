package com.csd.occ.bunching.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "alert_processing_state", schema = "bunching")
public class AlertProcessingState {
    protected AlertProcessingState() { }
    @Id private UUID id;
    private UUID demoRunId; private String serviceId; private String stopCode; private int directionId;
    private Instant lastFetchedAt; private UUID activeAlertId; private UUID leadingObservationId; private UUID followingObservationId;
    private Integer pendingHeadwaySeconds; private Instant pendingDetectedAt; private UUID pendingLeadingObservationId; private UUID pendingFollowingObservationId; private UUID pendingRawObservationId; private UUID pendingPollRunId;
    public Instant lastFetchedAt() { return lastFetchedAt; } public UUID activeAlertId() { return activeAlertId; }
    public UUID leadingObservationId() { return leadingObservationId; } public UUID followingObservationId() { return followingObservationId; }
    public Integer pendingHeadwaySeconds() { return pendingHeadwaySeconds; } public Instant pendingDetectedAt() { return pendingDetectedAt; }
    public UUID pendingLeadingObservationId() { return pendingLeadingObservationId; } public UUID pendingFollowingObservationId() { return pendingFollowingObservationId; }
    public void update(Instant fetchedAt, UUID alert) { lastFetchedAt = fetchedAt; activeAlertId = alert; }
    public void update(Instant fetchedAt, UUID alert, UUID leading, UUID following) { lastFetchedAt = fetchedAt; activeAlertId = alert; leadingObservationId = leading; followingObservationId = following; }
    public void recordCandidate(Instant fetchedAt, int seconds, UUID leading, UUID following, UUID raw, UUID poll) { lastFetchedAt = fetchedAt; pendingHeadwaySeconds = seconds; pendingDetectedAt = fetchedAt; pendingLeadingObservationId = leading; pendingFollowingObservationId = following; pendingRawObservationId = raw; pendingPollRunId = poll; }
    public void clearCandidate() { pendingHeadwaySeconds = null; pendingDetectedAt = null; pendingLeadingObservationId = null; pendingFollowingObservationId = null; pendingRawObservationId = null; pendingPollRunId = null; }
}
