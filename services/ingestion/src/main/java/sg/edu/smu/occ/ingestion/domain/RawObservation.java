package sg.edu.smu.occ.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "raw_observation", schema = "ingestion")
public class RawObservation {
    
    @Id
    private UUID id;
    private UUID pollRunId;
    private String source;
    private String sourceKey;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String requestParameters;
    
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;
    @Column(name = "payload_checksum", length = 64, nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String payloadChecksum;
    private OffsetDateTime observedAt;
    private OffsetDateTime fetchedAt;
    private OffsetDateTime ingestedAt;
    private Integer httpStatus;
    private String classification;

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
    public OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(OffsetDateTime observedAt) { this.observedAt = observedAt; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(OffsetDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(OffsetDateTime ingestedAt) { this.ingestedAt = ingestedAt; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
}
