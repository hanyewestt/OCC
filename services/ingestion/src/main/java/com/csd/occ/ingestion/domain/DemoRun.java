package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_run", schema = "ingestion")
public class DemoRun {
    protected DemoRun() {}
    public DemoRun(UUID id, Instant startedAt, String scenario, long seed) {
        this.id = id;
        int versionSeparator = scenario.lastIndexOf("-v");
        scenarioKey = scenario.substring(0, versionSeparator);
        scenarioVersion = scenario.substring(versionSeparator + 1);
        this.seed = seed;
        status = "PREPARING"; currentStep = 0; this.startedAt = startedAt; classification = "SIMULATION";
    }
    @Id private UUID id;
    private String scenarioKey;
    private String scenarioVersion;
    private long seed;
    private String status;
    private int currentStep;
    private Instant startedAt;
    private Instant completedAt;
    private Instant closedAt;
    private String failureReason;
    private String classification;
    public UUID id() { return id; }
    public String status() { return status; }
    public String classification() { return classification; }
    public int currentStep() { return currentStep; }
    public Instant startedAt() { return startedAt; }
    public void startReplay() { if (!"PREPARING".equals(status)) throw new IllegalStateException("Demo run is not preparing"); status = "RUNNING"; }
    public void advance() { currentStep++; }
    public void complete(Instant now) { status = "REPLAY_COMPLETE"; completedAt = now; }
    public void close(Instant now) { status = "CLOSED"; closedAt = now; }
    public void fail(String reason) { status = "FAILED"; failureReason = reason; }
}
