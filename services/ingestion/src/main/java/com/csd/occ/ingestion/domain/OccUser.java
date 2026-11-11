package com.csd.occ.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A read projection; these values never grant JWT authorities or operational permissions. */
@Entity
@Table(name = "occ_user", schema = "ingestion")
public class OccUser {
    @Id private UUID id;
    private String displayName;
    private String email;
    @Enumerated(EnumType.STRING) private OccRole role;
    private boolean available;
    private Instant createdAt;
    // Nullable: the provider account may never have signed in.
    private Instant lastSignInAt;
    private Instant synchronizedAt;
    // Full scans observe at scan start; direct confirmations observe when the provider result is confirmed.
    private Instant observedAt;

    protected OccUser() { }
    public OccUser(IdentityProviderPort.User user, Instant observedAt, Instant synchronizedAt) {
        id = user.id();
        update(user, observedAt, synchronizedAt);
    }

    public void update(IdentityProviderPort.User user, Instant observedAt, Instant synchronizedAt) {
        if (!id.equals(user.id())) throw new IllegalArgumentException("Directory identity cannot change");
        displayName = user.displayName(); email = user.email(); role = user.role(); available = user.available();
        createdAt = user.createdAt(); lastSignInAt = user.lastSignInAt(); this.synchronizedAt = synchronizedAt;
        this.observedAt = observedAt;
    }

    public void markUnavailable(Instant observedAt, Instant synchronizedAt) {
        available = false; this.observedAt = observedAt; this.synchronizedAt = synchronizedAt;
    }
    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public String email() { return email; }
    public OccRole role() { return role; }
    public boolean available() { return available; }
    public Instant createdAt() { return createdAt; }
    public Instant lastSignInAt() { return lastSignInAt; }
    public Instant synchronizedAt() { return synchronizedAt; }
    public Instant observedAt() { return observedAt; }
}
