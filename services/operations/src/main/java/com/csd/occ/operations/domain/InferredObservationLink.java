package com.csd.occ.operations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inferred_observation_link", schema = "operations")
public class InferredObservationLink {
    @Id private UUID observationId;
    private UUID trackId;
    private Instant fetchedAt;
    private String outcome;
    protected InferredObservationLink() { }
    public InferredObservationLink(LiveMapPort.Observation observation, UUID trackId, String outcome) {
        this.observationId = observation.observationId(); this.trackId = trackId;
        this.fetchedAt = observation.fetchedAt(); this.outcome = outcome;
    }
    public UUID getObservationId() { return observationId; }
    public UUID getTrackId() { return trackId; }
}
