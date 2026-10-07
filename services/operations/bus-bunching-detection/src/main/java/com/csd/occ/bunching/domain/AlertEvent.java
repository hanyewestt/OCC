package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "alert_event", schema = "bunching")
public class AlertEvent {
    protected AlertEvent() { }

    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence) {
        this(alertId, action, serviceId, stopCode, directionId, rawObservationId, pollRunId, fetchedAt,
                triggeredAt, evidence, "OBSERVATION_ONLY", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence,
                      String classification, UUID demoRunId) {
        this(alertId, action, serviceId, stopCode, directionId, rawObservationId, pollRunId, fetchedAt,
                triggeredAt, evidence, classification, demoRunId, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence,
                      String classification, UUID demoRunId, UUID leadingObservationId, UUID followingObservationId) {
        this(alertId, action, serviceId, stopCode, directionId, rawObservationId, pollRunId, fetchedAt,
                triggeredAt, evidence, classification, demoRunId, leadingObservationId, followingObservationId,
                null, null, null, null, null, null, null, null, null, null);
    }

    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence,
                      String classification, UUID demoRunId, UUID leadingObservationId, UUID followingObservationId,
                      Integer expectedHeadwaySeconds, Integer firstObservedHeadwaySeconds,
                      Integer secondObservedHeadwaySeconds, Instant firstConfirmingAt, Instant secondConfirmingAt,
                      UUID firstLeadingObservationId, UUID firstFollowingObservationId,
                      UUID secondLeadingObservationId, UUID secondFollowingObservationId,
                      String detectionRuleVersion) {
        id = UUID.randomUUID();
        this.alertId = alertId;
        this.action = action;
        this.classification = classification;
        this.demoRunId = demoRunId;
        this.leadingObservationId = leadingObservationId;
        this.followingObservationId = followingObservationId;
        this.serviceId = serviceId;
        this.stopCode = stopCode;
        this.directionId = directionId;
        this.rawObservationId = rawObservationId;
        this.pollRunId = pollRunId;
        this.fetchedAt = fetchedAt;
        this.triggeredAt = triggeredAt;
        this.evidence = evidence;
        this.expectedHeadwaySeconds = expectedHeadwaySeconds;
        this.firstObservedHeadwaySeconds = firstObservedHeadwaySeconds;
        this.secondObservedHeadwaySeconds = secondObservedHeadwaySeconds;
        this.firstConfirmingAt = firstConfirmingAt;
        this.secondConfirmingAt = secondConfirmingAt;
        this.firstLeadingObservationId = firstLeadingObservationId;
        this.firstFollowingObservationId = firstFollowingObservationId;
        this.secondLeadingObservationId = secondLeadingObservationId;
        this.secondFollowingObservationId = secondFollowingObservationId;
        this.detectionRuleVersion = detectionRuleVersion;
    }

    @Id private UUID id;
    private UUID alertId;
    private String action;
    private String classification;
    private UUID demoRunId;
    private UUID leadingObservationId;
    private UUID followingObservationId;
    private String serviceId;
    private String stopCode;
    private int directionId;
    private UUID rawObservationId;
    private UUID pollRunId;
    private Instant fetchedAt;
    private Instant triggeredAt;
    @JdbcTypeCode(SqlTypes.JSON) private String evidence;
    private Integer expectedHeadwaySeconds;
    private Integer firstObservedHeadwaySeconds;
    private Integer secondObservedHeadwaySeconds;
    private Instant firstConfirmingAt;
    private Instant secondConfirmingAt;
    private UUID firstLeadingObservationId;
    private UUID firstFollowingObservationId;
    private UUID secondLeadingObservationId;
    private UUID secondFollowingObservationId;
    private String detectionRuleVersion;

    public UUID id() { return id; }
    public UUID alertId() { return alertId; }
    public String action() { return action; }
    public String serviceId() { return serviceId; }
    public String stopCode() { return stopCode; }
    public int directionId() { return directionId; }
    public Instant triggeredAt() { return triggeredAt; }
    public Instant fetchedAt() { return fetchedAt; }
    public String evidence() { return evidence; }
    public String classification() { return classification; }
    public UUID demoRunId() { return demoRunId; }
    public UUID leadingObservationId() { return leadingObservationId; }
    public UUID followingObservationId() { return followingObservationId; }
    public UUID rawObservationId() { return rawObservationId; }
    public UUID pollRunId() { return pollRunId; }
    public Integer expectedHeadwaySeconds() { return expectedHeadwaySeconds; }
    public Integer firstObservedHeadwaySeconds() { return firstObservedHeadwaySeconds; }
    public Integer secondObservedHeadwaySeconds() { return secondObservedHeadwaySeconds; }
    public Instant firstConfirmingAt() { return firstConfirmingAt; }
    public Instant secondConfirmingAt() { return secondConfirmingAt; }
    public UUID firstLeadingObservationId() { return firstLeadingObservationId; }
    public UUID firstFollowingObservationId() { return firstFollowingObservationId; }
    public UUID secondLeadingObservationId() { return secondLeadingObservationId; }
    public UUID secondFollowingObservationId() { return secondFollowingObservationId; }
    public String detectionRuleVersion() { return detectionRuleVersion; }

    public List<UUID> confirmingObservationIds() {
        return java.util.stream.Stream.of(firstLeadingObservationId, firstFollowingObservationId,
                secondLeadingObservationId, secondFollowingObservationId).filter(Objects::nonNull).toList();
    }

    public List<String> detectionEvidenceIds() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("bunching-alert-event:" + id),
                confirmingObservationIds().stream().map(value -> "bus-arrival:" + value)).toList();
    }
}
