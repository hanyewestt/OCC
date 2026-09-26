package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bus_arrival_observation", schema = "ingestion")
public class BusArrivalObservation {
    protected BusArrivalObservation() {
    }

    @Id
    private UUID id;
    private UUID rawObservationId;
    private UUID pollRunId;
    private UUID referenceVersionId;
    private String stopCode;
    private String serviceId;
    private String operator;
    private Short directionId;
    private Short visitOrder;
    private Short visitNumber;
    private Instant estimatedArrivalAt;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String loadCategory;
    private String feature;
    private Instant fetchedAt;
    private Instant ingestedAt;
    @Column(name = "record_checksum", columnDefinition = "char(64)", length = 64, nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String recordChecksum;
    private String originCode;
    private String destinationCode;
    private Boolean monitored;
    private String vehicleType;
    private String classification = "OBSERVATION_ONLY";
    private UUID demoRunId;
    private String tripId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRawObservationId() { return rawObservationId; }
    public void setRawObservationId(UUID rawObservationId) { this.rawObservationId = rawObservationId; }
    public UUID getPollRunId() { return pollRunId; }
    public void setPollRunId(UUID pollRunId) { this.pollRunId = pollRunId; }
    public UUID getReferenceVersionId() { return referenceVersionId; }
    public void setReferenceVersionId(UUID referenceVersionId) { this.referenceVersionId = referenceVersionId; }
    public String getStopCode() { return stopCode; }
    public void setStopCode(String stopCode) { this.stopCode = stopCode; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Integer getDirectionId() { return directionId == null ? null : directionId.intValue(); }
    public void setDirectionId(Integer directionId) { this.directionId = directionId == null ? null : directionId.shortValue(); }
    public Integer getVisitOrder() { return visitOrder == null ? null : visitOrder.intValue(); }
    public void setVisitOrder(Integer visitOrder) { this.visitOrder = visitOrder == null ? null : visitOrder.shortValue(); }
    public Integer getVisitNumber() { return visitNumber == null ? null : visitNumber.intValue(); }
    public void setVisitNumber(Integer visitNumber) { this.visitNumber = visitNumber == null ? null : visitNumber.shortValue(); }
    public Instant getEstimatedArrivalAt() { return estimatedArrivalAt; }
    public void setEstimatedArrivalAt(Instant estimatedArrivalAt) { this.estimatedArrivalAt = estimatedArrivalAt; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getLoadCategory() { return loadCategory; }
    public void setLoadCategory(String loadCategory) { this.loadCategory = loadCategory; }
    public String getFeature() { return feature; }
    public void setFeature(String feature) { this.feature = feature; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
    public String getRecordChecksum() { return recordChecksum; }
    public void setRecordChecksum(String recordChecksum) { this.recordChecksum = recordChecksum; }
    public String getOriginCode() { return originCode; }
    public void setOriginCode(String originCode) { this.originCode = originCode; }
    public String getDestinationCode() { return destinationCode; }
    public void setDestinationCode(String destinationCode) { this.destinationCode = destinationCode; }
    public Boolean getMonitored() { return monitored; }
    public void setMonitored(Boolean monitored) { this.monitored = monitored; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public UUID getDemoRunId() { return demoRunId; }
    public void setDemoRunId(UUID demoRunId) { this.demoRunId = demoRunId; }
    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    @PrePersist
    void defaultToLiveClassification() {
        if (classification == null) classification = "OBSERVATION_ONLY";
    }
}
