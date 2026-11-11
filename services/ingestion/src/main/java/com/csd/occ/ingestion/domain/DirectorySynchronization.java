package com.csd.occ.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "directory_synchronization", schema = "ingestion")
public class DirectorySynchronization {
    @Id private short id;
    // Nullable until the first complete successful provider scan.
    private Instant scanStartedAt;
    // Nullable until the first complete successful provider scan.
    private Instant synchronizedAt;

    protected DirectorySynchronization() { }
    public Instant scanStartedAt() { return scanStartedAt; }
    public Instant synchronizedAt() { return synchronizedAt; }
    public void complete(Instant startedAt, Instant completedAt) { scanStartedAt = startedAt; synchronizedAt = completedAt; }
}
