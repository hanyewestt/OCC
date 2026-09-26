package com.csd.occ.ingestion.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity
@Table(name = "traffic_incident_observation", schema = "ingestion")
public class TrafficIncidentObservation {
    protected TrafficIncidentObservation() {}
    @Id private UUID id;
    private UUID rawObservationId;
    private UUID pollRunId;
    @Column(name = "source_incident_key", columnDefinition = "char(64)", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String sourceIncidentKey;
    private String incidentType;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String message;
    private Instant observedAt;
    private Instant fetchedAt;
    private Instant ingestedAt;
    private String classification;
    private UUID demoRunId;
    public TrafficIncidentObservation(UUID rawId, UUID pollId, UUID runId, String key, Instant now) {
        id = UUID.randomUUID(); rawObservationId = rawId; pollRunId = pollId; demoRunId = runId;
        sourceIncidentKey = key; incidentType = "Vehicle Breakdown"; latitude = new BigDecimal("1.3802550");
        longitude = new BigDecimal("103.7550750"); message = "DEMO: Vehicle breakdown on Teck Whye Ave.";
        fetchedAt = now; ingestedAt = now; classification = "SIMULATION";
    }
    public String incidentType() { return incidentType; }
    public BigDecimal latitude() { return latitude; }
    public BigDecimal longitude() { return longitude; }
    public String message() { return message; }
    public Instant fetchedAt() { return fetchedAt; }
}
