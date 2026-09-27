package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "alert_event", schema = "bunching")
public class AlertEvent {
    protected AlertEvent() {
    }

    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence) {
        this(alertId, action, serviceId, stopCode, directionId, rawObservationId, pollRunId, fetchedAt, triggeredAt,
                evidence, "OBSERVATION_ONLY", null);
    }
    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence,
                      String classification, UUID demoRunId) {
        this(alertId, action, serviceId, stopCode, directionId, rawObservationId, pollRunId, fetchedAt,
                triggeredAt, evidence, classification, demoRunId, null, null);
    }
    public AlertEvent(UUID alertId, String action, String serviceId, String stopCode, int directionId,
                      UUID rawObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence,
                      String classification, UUID demoRunId, UUID leadingObservationId, UUID followingObservationId) {
        id = UUID.randomUUID();
        this.alertId = alertId;
        this.action = action;
        actor = "SYSTEM:BUS_BUNCHING_DETECTION";
        this.classification = classification;
        this.demoRunId = demoRunId;
        feedScope = demoRunId == null ? "LIVE" : "DEMO:" + demoRunId;
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
    }

    @Id
    private UUID id;
    private UUID alertId;
    private String action;
    private String actor;
    private String classification;
    private UUID demoRunId;
    private String feedScope;
    private UUID leadingObservationId;
    private UUID followingObservationId;
    private String serviceId;
    private String stopCode;
    private int directionId;
    private UUID rawObservationId;
    private UUID pollRunId;
    private Instant fetchedAt;
    private Instant triggeredAt;
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidence;

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
}
