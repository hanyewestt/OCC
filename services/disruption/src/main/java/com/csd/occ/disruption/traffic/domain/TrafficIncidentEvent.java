package com.csd.occ.disruption.traffic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "disruption_event", schema = "disruption")
public class TrafficIncidentEvent {
    protected TrafficIncidentEvent() { }

    public TrafficIncidentEvent(UUID id, UUID disruptionId, String action, String classification, UUID demoRunId,
            UUID sourceObservationId, UUID pollRunId, Instant fetchedAt, Instant triggeredAt, String evidence) {
        this.id = id;
        this.disruptionId = disruptionId;
        kind = "TRAFFIC_INCIDENT";
        this.action = action;
        this.classification = classification;
        this.demoRunId = demoRunId;
        this.sourceObservationId = sourceObservationId;
        this.pollRunId = pollRunId;
        this.fetchedAt = fetchedAt;
        this.triggeredAt = triggeredAt;
        this.evidence = evidence;
    }

    @Id private UUID id;
    @Column(name = "disruption_id") private UUID disruptionId;
    private String kind;
    private String action;
    private String classification;
    private UUID demoRunId;
    private UUID sourceObservationId;
    private UUID pollRunId;
    private Instant fetchedAt;
    private Instant triggeredAt;
    @JdbcTypeCode(SqlTypes.JSON) private String evidence;
}
