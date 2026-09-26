package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "inferred_track_frame", schema = "operations")
public class InferredTrackFrame {
    @Id private String serviceId;
    private Instant sourceAt;
    @jakarta.persistence.ElementCollection
    @jakarta.persistence.CollectionTable(name = "inferred_track_frame_member", schema = "operations",
            joinColumns = @jakarta.persistence.JoinColumn(name = "service_id"))
    @jakarta.persistence.Column(name = "track_id")
    private java.util.Set<java.util.UUID> trackIds = new java.util.HashSet<>();
    protected InferredTrackFrame() { }
    public InferredTrackFrame(String serviceId, Instant sourceAt) { this.serviceId=serviceId; this.sourceAt=sourceAt; }
    public String getServiceId() { return serviceId; }
    public Instant getSourceAt() { return sourceAt; }
    public java.util.Set<java.util.UUID> getTrackIds() { return trackIds; }
}
