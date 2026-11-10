package com.csd.occ.disruption.bunching.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name = "disruption_processing_state", schema = "disruption")
public class AlertProcessingState {
    protected AlertProcessingState() { }
    @Id private UUID id;
    private UUID demoRunId; private String serviceId; private String stopCode; private int directionId;
    private Instant lastFetchedAt; @jakarta.persistence.Column(name = "active_disruption_id") private UUID activeAlertId; private UUID leadingObservationId; private UUID followingObservationId;
    private Integer pendingHeadwaySeconds; private Instant pendingDetectedAt; private UUID pendingLeadingObservationId; private UUID pendingFollowingObservationId; private UUID pendingRawObservationId; private UUID pendingPollRunId;
    @JdbcTypeCode(SqlTypes.JSON) private List<UUID> pendingAffectedObservationIds = List.of();
    private String currentBunchingState;
    private UUID currentRawObservationId;
    private Instant currentEvidenceValidUntil;
    @JdbcTypeCode(SqlTypes.JSON) private List<UUID> currentAffectedObservationIds;
    public Instant lastFetchedAt() { return lastFetchedAt; } public UUID activeAlertId() { return activeAlertId; }
    public UUID leadingObservationId() { return leadingObservationId; } public UUID followingObservationId() { return followingObservationId; }
    public Integer pendingHeadwaySeconds() { return pendingHeadwaySeconds; } public Instant pendingDetectedAt() { return pendingDetectedAt; }
    public UUID pendingLeadingObservationId() { return pendingLeadingObservationId; } public UUID pendingFollowingObservationId() { return pendingFollowingObservationId; }
    public List<UUID> pendingAffectedObservationIds() { return pendingAffectedObservationIds == null ? List.of() : pendingAffectedObservationIds; }
    public String serviceId() { return serviceId; }
    public String stopCode() { return stopCode; }
    public int directionId() { return directionId; }
    public String currentBunchingState() { return currentBunchingState; }
    public UUID currentRawObservationId() { return currentRawObservationId; }
    public Instant currentEvidenceValidUntil() { return currentEvidenceValidUntil; }
    public List<UUID> currentAffectedObservationIds() { return currentAffectedObservationIds; }
    public void recordCurrentEvidence(String state, UUID rawObservationId, Instant validUntil, List<UUID> affected) {
        currentBunchingState = state;
        currentRawObservationId = rawObservationId;
        currentEvidenceValidUntil = validUntil;
        currentAffectedObservationIds = List.copyOf(affected);
    }
    public void update(Instant fetchedAt, UUID alert) { lastFetchedAt = fetchedAt; activeAlertId = alert; }
    public void update(Instant fetchedAt, UUID alert, UUID leading, UUID following) { lastFetchedAt = fetchedAt; activeAlertId = alert; leadingObservationId = leading; followingObservationId = following; }
    public void recordCandidate(Instant fetchedAt, int seconds, UUID leading, UUID following, UUID raw, UUID poll) { lastFetchedAt = fetchedAt; pendingHeadwaySeconds = seconds; pendingDetectedAt = fetchedAt; pendingLeadingObservationId = leading; pendingFollowingObservationId = following; pendingRawObservationId = raw; pendingPollRunId = poll; }
    public void recordCandidate(Instant fetchedAt, int seconds, UUID leading, UUID following, UUID raw, UUID poll, List<UUID> affected) { recordCandidate(fetchedAt, seconds, leading, following, raw, poll); pendingAffectedObservationIds = List.copyOf(affected); }
    public void clearCandidate() { pendingHeadwaySeconds = null; pendingDetectedAt = null; pendingLeadingObservationId = null; pendingFollowingObservationId = null; pendingRawObservationId = null; pendingPollRunId = null; pendingAffectedObservationIds = List.of(); }
}
