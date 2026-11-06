package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "demo_run", schema = "ingestion")
public class DemoRun {
    protected DemoRun() {}
    public DemoRun(UUID id, Instant startedAt, String scenario, long seed) {
        this(id, startedAt, scenario, seed, DemoRunConfiguration.defaults());
    }
    public DemoRun(UUID id, Instant startedAt, String scenario, long seed, DemoRunConfiguration configuration) {
        this.id = id;
        int versionSeparator = scenario.lastIndexOf("-v");
        scenarioKey = scenario.substring(0, versionSeparator);
        scenarioVersion = scenario.substring(versionSeparator + 1);
        this.seed = seed;
        serviceId = configuration.serviceId(); directionId = (short) configuration.directionId();
        busCount = (short) configuration.busCount(); dispatchGapSeconds = configuration.dispatchGap().toSeconds();
        busGapSeconds = configuration.busGaps().stream().map(java.time.Duration::toSeconds).toList();
        monitoredStopCode = configuration.monitoredStopCode();
        simulationScenario = configuration.scenario().name();
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
    private String serviceId;
    private Short directionId;
    private Short busCount;
    private Long dispatchGapSeconds;
    @JdbcTypeCode(SqlTypes.JSON) private List<Long> busGapSeconds;
    private String monitoredStopCode;
    private String simulationScenario;
    public UUID id() { return id; }
    public String status() { return status; }
    public String classification() { return classification; }
    public int currentStep() { return currentStep; }
    public Instant startedAt() { return startedAt; }
    public DemoRunConfiguration configuration() {
        List<java.time.Duration> gaps = busGapSeconds == null ? java.util.stream.IntStream.range(1, busCount)
                .mapToObj(ignored -> java.time.Duration.ofSeconds(dispatchGapSeconds)).toList()
                : busGapSeconds.stream().map(java.time.Duration::ofSeconds).toList();
        return new DemoRunConfiguration(serviceId, directionId, busCount, java.time.Duration.ofSeconds(dispatchGapSeconds), gaps,
                monitoredStopCode == null ? "44251" : monitoredStopCode,
                DemoScenario.valueOf(simulationScenario));
    }
    public boolean expiredAt(Instant cutoff) {
        return "CLOSED".equals(status) && closedAt != null && closedAt.isBefore(cutoff)
                || "FAILED".equals(status) && startedAt.isBefore(cutoff);
    }
    public void startReplay() { if (!"PREPARING".equals(status)) throw new IllegalStateException("Demo run is not preparing"); status = "RUNNING"; }
    public void advance() { currentStep++; }
    public void complete(Instant now) { status = "REPLAY_COMPLETE"; completedAt = now; }
    public void close(Instant now) { status = "CLOSED"; closedAt = now; }
    public void fail(String reason) { status = "FAILED"; failureReason = reason; }
}
