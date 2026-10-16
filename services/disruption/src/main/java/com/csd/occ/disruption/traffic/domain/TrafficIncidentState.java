package com.csd.occ.disruption.traffic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "traffic_incident_state", schema = "disruption")
public class TrafficIncidentState {
    protected TrafficIncidentState() { }

    public TrafficIncidentState(UUID id, UUID disruptionId, TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact incident, String snapshotHash, Instant now) {
        this.id = id;
        this.disruptionId = disruptionId;
        classification = poll.classification();
        demoRunId = poll.demoRunId();
        source = poll.source();
        sourceIncidentKey = incident.sourceIncidentKey();
        incidentType = incident.incidentType();
        message = incident.message();
        latitude = incident.latitude();
        longitude = incident.longitude();
        observedAt = incident.observedAt();
        fetchedAt = incident.fetchedAt();
        sourceObservationId = incident.id();
        lastPollRunId = poll.pollRunId();
        this.snapshotHash = snapshotHash;
        status = "ACTIVE";
        openedAt = now;
        updatedAt = now;
        lastProcessedPollAt = poll.completedAt();
    }

    @Id private UUID id;
    private String classification;
    private UUID demoRunId;
    private String source;
    @Column(name = "source_incident_key", columnDefinition = "char(64)", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR) private String sourceIncidentKey;
    private String incidentType;
    private String message;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Instant observedAt;
    private Instant fetchedAt;
    private UUID sourceObservationId;
    private UUID lastPollRunId;
    @Column(name = "snapshot_hash", columnDefinition = "char(64)", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR) private String snapshotHash;
    private String status;
    private int absenceCount;
    private Instant openedAt;
    private Instant updatedAt;
    private Instant clearedAt;
    private UUID disruptionId;
    private Instant lastProcessedPollAt;
    @Version private long version;

    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean hasSnapshot(String hash) { return snapshotHash.equals(hash); }
    public UUID id() { return id; }
    public String classification() { return classification; }
    public UUID demoRunId() { return demoRunId; }
    public String source() { return source; }
    public String sourceIncidentKey() { return sourceIncidentKey; }
    public String incidentType() { return incidentType; }
    public String message() { return message; }
    public BigDecimal latitude() { return latitude; }
    public BigDecimal longitude() { return longitude; }
    public Instant observedAt() { return observedAt; }
    public Instant fetchedAt() { return fetchedAt; }
    public UUID sourceObservationId() { return sourceObservationId; }
    public UUID lastPollRunId() { return lastPollRunId; }
    public String snapshotHash() { return snapshotHash; }
    public int absenceCount() { return absenceCount; }
    public Instant openedAt() { return openedAt; }
    public Instant updatedAt() { return updatedAt; }
    public UUID disruptionId() { return disruptionId; }

    public void observe(TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact incident, String hash, Instant now) {
        source = poll.source();
        incidentType = incident.incidentType();
        message = incident.message();
        latitude = incident.latitude();
        longitude = incident.longitude();
        observedAt = incident.observedAt();
        fetchedAt = incident.fetchedAt();
        sourceObservationId = incident.id();
        lastPollRunId = poll.pollRunId();
        snapshotHash = hash;
        absenceCount = 0;
        lastProcessedPollAt = poll.completedAt();
        updatedAt = now;
    }

    public void markPresentWithoutChange(TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact incident) {
        if (incident.fetchedAt() != null && (fetchedAt == null || incident.fetchedAt().isAfter(fetchedAt))) {
            fetchedAt = incident.fetchedAt();
            observedAt = incident.observedAt();
            sourceObservationId = incident.id();
            lastPollRunId = poll.pollRunId();
        }
        absenceCount = 0;
        lastProcessedPollAt = poll.completedAt();
    }

    public void markAbsent(Instant completedAt) {
        absenceCount++;
        lastProcessedPollAt = completedAt;
    }

    public void clear(Instant completedAt) {
        status = "CLEARED";
        clearedAt = completedAt;
        updatedAt = completedAt;
    }

    public void reopen(UUID newDisruptionId, TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact incident, String hash, Instant now) {
        disruptionId = newDisruptionId;
        status = "ACTIVE";
        openedAt = now;
        clearedAt = null;
        observe(poll, incident, hash, now);
    }
}
