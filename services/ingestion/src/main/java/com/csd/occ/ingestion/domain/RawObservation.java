package com.csd.occ.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "raw_observation", schema = "ingestion")
public class RawObservation {
    protected RawObservation() {}
    
    @Id
    private UUID id;
    private UUID pollRunId;
    private String source;
    private String sourceKey;
    // Narrow persistence exception to domain import rules: Hibernate type hints
    // preserve the existing PostgreSQL jsonb/char mappings without changing DDL.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String requestParameters;
    
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;
    @Column(name = "payload_checksum", length = 64, nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String payloadChecksum;
    private Instant observedAt;
    private Instant fetchedAt;
    private Instant ingestedAt;
    private Integer httpStatus;
    private String classification;
    @Column(insertable = false, updatable = false)
    private Long feedSequence;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPollRunId() { return pollRunId; }
    public void setPollRunId(UUID pollRunId) { this.pollRunId = pollRunId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getRequestParameters() { return requestParameters; }
    public void setRequestParameters(String requestParameters) { this.requestParameters = requestParameters; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getPayloadChecksum() { return payloadChecksum; }
    public void setPayloadChecksum(String payloadChecksum) { this.payloadChecksum = payloadChecksum; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public Long getFeedSequence() { return feedSequence; }
}
