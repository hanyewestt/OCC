package com.csd.occ.bunching.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only observation audit. Source IDs deliberately outlive telemetry retention. */
@Entity
@Table(name = "bunching_observation", schema = "bunching")
public class BunchingObservation {
    @Id
    @Column(name = "raw_observation_id", nullable = false)
    private UUID rawObservationId;

    @Column(name = "poll_run_id", nullable = false)
    private UUID pollRunId;

    // Nullable when stored raw input has no usable imported topology; never invent a version.
    @Column(name = "reference_version_id")
    private UUID referenceVersionId;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "threshold_seconds", nullable = false, precision = 30, scale = 9)
    private BigDecimal thresholdSeconds;

    @Column(name = "stale_after_seconds", nullable = false, precision = 30, scale = 9)
    private BigDecimal staleAfterSeconds;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "classification", nullable = false)
    private String classification;

    @Column(name = "feed_scope", nullable = false)
    private String feedScope;

    @Column(name = "demo_run_id")
    private UUID demoRunId;

    @Column(name = "bunched_group_count", nullable = false)
    private int bunchedGroupCount;

    // JSON writes use an explicit PostgreSQL cast in the repository, not ORM serialization.
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb", insertable = false, updatable = false)
    private String evidence;

    protected BunchingObservation() {}

    public UUID rawObservationId() { return rawObservationId; }
    public UUID pollRunId() { return pollRunId; }
    public UUID referenceVersionId() { return referenceVersionId; }
    public Instant fetchedAt() { return fetchedAt; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public BigDecimal thresholdSeconds() { return thresholdSeconds; }
    public BigDecimal staleAfterSeconds() { return staleAfterSeconds; }
    public String ruleVersion() { return ruleVersion; }
    public String classification() { return classification; }
    public String feedScope() { return feedScope; }
    public UUID demoRunId() { return demoRunId; }
    public int bunchedGroupCount() { return bunchedGroupCount; }
    public String evidence() { return evidence; }
}
