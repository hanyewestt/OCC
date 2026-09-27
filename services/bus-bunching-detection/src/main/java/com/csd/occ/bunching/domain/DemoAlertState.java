package com.csd.occ.bunching.domain;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "demo_alert_state", schema = "bunching")
public class DemoAlertState {
    protected DemoAlertState() {}
    @EmbeddedId private DemoAlertStateId id;
    private Instant lastFetchedAt;
    private UUID activeAlertId;
    private UUID leadingObservationId;
    private UUID followingObservationId;
    public Instant lastFetchedAt() { return lastFetchedAt; }
    public UUID activeAlertId() { return activeAlertId; }
    public UUID leadingObservationId() { return leadingObservationId; }
    public UUID followingObservationId() { return followingObservationId; }
    public void update(Instant fetchedAt, UUID activeAlertId, UUID leadingObservationId, UUID followingObservationId) {
        lastFetchedAt = fetchedAt; this.activeAlertId = activeAlertId;
        this.leadingObservationId = leadingObservationId; this.followingObservationId = followingObservationId;
    }
}
