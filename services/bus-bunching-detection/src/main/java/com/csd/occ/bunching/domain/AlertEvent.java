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
        id = UUID.randomUUID();
        this.alertId = alertId;
        this.action = action;
        actor = "SYSTEM:BUS_BUNCHING_DETECTION";
        classification = "OBSERVATION_ONLY";
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
}
