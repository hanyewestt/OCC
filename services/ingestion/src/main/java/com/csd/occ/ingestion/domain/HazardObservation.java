package com.csd.occ.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** Maps additional kinds into the existing canonical observation table; never copies traffic rows. */
@Entity
@Table(name = "traffic_incident_observation", schema = "ingestion")
@SQLRestriction("hazard_kind <> 'TRAFFIC_INCIDENT'")
public class HazardObservation {
    @Id private UUID id;
    private UUID rawObservationId;
    private UUID pollRunId;
    @Column(name = "source_incident_key", columnDefinition = "char(64)", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR) private String sourceIncidentKey;
    private String incidentType;
    private String message;
    private Instant observedAt;
    private Instant fetchedAt;
    private Instant ingestedAt;
    private String classification;
    // Null for observations from live providers.
    private UUID demoRunId;
    @Enumerated(EnumType.STRING) private HazardKind hazardKind;
    @JdbcTypeCode(SqlTypes.JSON) private HazardFeedPort.Fact hazardFacts;
    protected HazardObservation() {}
    public HazardObservation(UUID rawId, UUID pollId, UUID demoRunId, String sourceKey,
            HazardFeedPort.Fact facts, Instant fetchedAt) {
        id = UUID.randomUUID(); rawObservationId = rawId; pollRunId = pollId; this.demoRunId = demoRunId;
        sourceIncidentKey = sourceKey; hazardKind = facts.kind(); hazardFacts = facts;
        incidentType = facts.kind().name();
        String description = facts.description() == null ? "" : facts.description();
        message = description.substring(0, Math.min(description.length(), 500));
        observedAt = facts.reportedAt(); this.fetchedAt = fetchedAt; ingestedAt = fetchedAt;
        classification = demoRunId == null ? "OBSERVATION_ONLY" : "SIMULATION";
    }
    public UUID id() { return id; }
    public UUID rawObservationId() { return rawObservationId; }
    public UUID pollRunId() { return pollRunId; }
    public HazardFeedPort.Fact facts() { return hazardFacts; }
    public Instant fetchedAt() { return fetchedAt; }
    public String classification() { return classification; }
    public UUID demoRunId() { return demoRunId; }
}
