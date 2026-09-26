package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inferred_vehicle_track", schema = "operations")
public class InferredVehicleTrack {
    @Id private UUID id;
    private String serviceId;
    private int directionId;
    private String operator;
    private String routeFingerprint;
    private double latitude;
    private double longitude;
    private double progressMeters;
    private String anchorStop;
    private Instant anchorEta;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private String status;
    private double confidence;
    private int confirmations;
    @Version private long version;

    protected InferredVehicleTrack() { }

    public InferredVehicleTrack(TrackInferencePolicy.Candidate candidate) {
        id = UUID.randomUUID();
        serviceId = candidate.serviceId(); directionId = candidate.directionId(); operator = candidate.operator();
        routeFingerprint = candidate.routeFingerprint();
        firstSeenAt = candidate.latest().fetchedAt(); lastSeenAt = firstSeenAt;
        confirmations = 1; confidence = .35; status = "PROVISIONAL";
        position(candidate);
    }

    public void observe(TrackInferencePolicy.Candidate candidate) {
        if (candidate.latest().fetchedAt().isAfter(lastSeenAt)) {
            confirmations++;
            lastSeenAt = candidate.latest().fetchedAt();
            position(candidate);
            // Confidence is a heuristic evidence score, not a calibrated probability.
            confidence = Math.min(.85, .35 + .1 * (confirmations - 1));
            status = confirmations >= 2 ? "ACTIVE" : "PROVISIONAL";
        }
    }

    private void position(TrackInferencePolicy.Candidate candidate) {
        latitude = candidate.latest().latitude().doubleValue(); longitude = candidate.latest().longitude().doubleValue();
        progressMeters = candidate.progressMeters(); anchorStop = candidate.latest().stopCode();
        anchorEta = candidate.latest().estimatedArrivalAt();
    }
    public void ambiguous() { status = "AMBIGUOUS"; confidence = Math.min(confidence, .2); }
    public void expire() { status = "EXPIRED"; }
    public UUID getId() { return id; }
    public String getServiceId() { return serviceId; }
    public int getDirectionId() { return directionId; }
    public String getOperator() { return operator; }
    public String getRouteFingerprint() { return routeFingerprint; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getProgressMeters() { return progressMeters; }
    public String getAnchorStop() { return anchorStop; }
    public Instant getAnchorEta() { return anchorEta; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getStatus() { return status; }
    public double getConfidence() { return confidence; }
    public int getConfirmations() { return confirmations; }
}
