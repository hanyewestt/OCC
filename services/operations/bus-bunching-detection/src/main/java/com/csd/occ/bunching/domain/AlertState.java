package com.csd.occ.bunching.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_state", schema = "bunching")
public class AlertState {
    protected AlertState() {
    }

    @EmbeddedId
    private AlertStateId id;
    private Instant lastFetchedAt;
    private UUID activeAlertId;

    public Instant lastFetchedAt() { return lastFetchedAt; }
    public UUID activeAlertId() { return activeAlertId; }
    public void update(Instant fetchedAt, UUID activeAlertId) {
        this.lastFetchedAt = fetchedAt;
        this.activeAlertId = activeAlertId;
    }
}
