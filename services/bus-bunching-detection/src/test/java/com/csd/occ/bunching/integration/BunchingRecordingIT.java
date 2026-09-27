package com.csd.occ.bunching.integration;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.BunchingEvaluation;
import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.BunchingRecordingService;
import com.csd.occ.bunching.domain.RecordedBunching;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "occ.bunching.consumer-enabled=false",
        "occ.bunching.stale-after=120s",
        "spring.datasource.url=jdbc:postgresql://unused/test",
        "spring.datasource.password=synthetic-test-only",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
@Import(BunchingRecordingIT.FixedTime.class)
class BunchingRecordingIT {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final Duration THRESHOLD = Duration.ofSeconds(90);
    private static final Duration FRESHNESS = Duration.ofSeconds(120);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("db/bunching-schema.sql");

    private final BunchingRecordingService service;
    private final BunchingObservationRepository observations;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final PlatformTransactionManager transactions;

    @Autowired
    BunchingRecordingIT(BunchingRecordingService service, BunchingObservationRepository observations,
                        JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager transactions) {
        this.service = service;
        this.observations = observations;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    @BeforeEach
    void resetFeedState() {
        jdbc.execute("TRUNCATE bunching.diagnosis_outbox, bunching.alert_state, bunching.demo_alert_state, bunching.alert_event, bunching.bunching_observation");
        jdbc.update("UPDATE bunching.consumer_selection SET mode='LIVE', demo_run_id=NULL, changed_at=? WHERE id=1",
                NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("UPDATE bunching.ingestion_feed_cursor SET feed_scope='LIVE', demo_run_id=NULL, last_sequence=0 WHERE id=1");
    }

    @Test
    void storesCompleteEvidenceAndSourceProvenance() {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var result = service.record(snapshot, THRESHOLD, FRESHNESS);
        var stored = observations.findByRawObservationId(snapshot.rawObservationId()).orElseThrow();
        var evidence = mapper.readValue(stored.evidence(), BunchingEvaluation.class);

        assertEquals(RecordedBunching.Outcome.RECORDED, result.outcome());
        assertEquals(snapshot, evidence.snapshot());
        assertEquals(snapshot.pollRunId(), stored.pollRunId());
        assertEquals(snapshot.referenceVersionId().orElseThrow(), stored.referenceVersionId());
        assertEquals(NOW, stored.fetchedAt());
        assertEquals(NOW, stored.evaluatedAt());
        assertEquals("OBSERVATION_ONLY", stored.classification());
        assertEquals(BunchingRecordingService.RULE_VERSION, stored.ruleVersion());
        assertEquals(1, stored.bunchedGroupCount());
        assertEquals(THRESHOLD, evidence.threshold());
        assertEquals(FRESHNESS, evidence.staleAfter());
        assertEquals(Duration.ofSeconds(60), evidence.groups().getFirst().headways().getFirst().gap());
    }

    @Test
    void retriesPreserveTheOriginalThresholdAndEvidence() {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var first = service.record(snapshot, THRESHOLD, FRESHNESS);
        String evidence = observations.findByRawObservationId(snapshot.rawObservationId()).orElseThrow().evidence();
        var retry = service.record(snapshot, Duration.ofSeconds(30), FRESHNESS);
        assertEquals(RecordedBunching.Outcome.ALREADY_RECORDED, retry.outcome());
        assertEquals(first.thresholdSeconds(), retry.thresholdSeconds());
        assertEquals(first.evaluatedAt(), retry.evaluatedAt());
        assertEquals(first.bunchedGroupCount(), retry.bunchedGroupCount());
        assertEquals(evidence, observations.findByRawObservationId(snapshot.rawObservationId()).orElseThrow().evidence());
        assertEquals(1, count(snapshot));
    }

    @Test
    void concurrentRetriesCommitExactlyOneRecord() throws Exception {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<RecordedBunching> task = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.record(snapshot, THRESHOLD, FRESHNESS);
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS).outcome(), second.get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, outcomes.stream().filter(outcome -> outcome == RecordedBunching.Outcome.RECORDED).count());
            assertEquals(1, count(snapshot));
        }
    }

    @Test
    void normalStaleAndMissingDirectionEvidenceAreAlsoAudited() {
        var normal = snapshot(NOW, 180, Optional.of(1));
        var stale = snapshot(NOW.minusSeconds(120), 60, Optional.of(1));
        var ambiguous = snapshot(NOW, 60, Optional.empty());
        for (var snapshot : List.of(normal, stale)) {
            assertEquals(0, service.record(snapshot, THRESHOLD, FRESHNESS).bunchedGroupCount());
        }
        assertEquals(1, service.record(ambiguous, THRESHOLD, FRESHNESS).bunchedGroupCount());
        assertEquals(BunchingEvaluation.State.NORMAL, evaluation(normal).groups().getFirst().state());
        assertEquals(BunchingEvaluation.Reason.STALE_SNAPSHOT, evaluation(stale).groups().getFirst().reason());
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation(ambiguous).groups().getFirst().state());
    }

    @Test
    void transactionRollbackDoesNotLeaveAnAuditRecord() {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.record(snapshot, THRESHOLD, FRESHNESS);
            throw new IllegalStateException("Synthetic downstream failure");
        }));
        assertEquals(0, count(snapshot));
        assertEquals(RecordedBunching.Outcome.RECORDED, service.record(snapshot, THRESHOLD, FRESHNESS).outcome());
    }

    @Test
    void databaseRejectsInvalidThresholdWithoutLeavingARow() {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var transaction = new TransactionTemplate(transactions);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> transaction.executeWithoutResult(status -> observations.insertIfAbsent(
                        snapshot.rawObservationId(), snapshot.pollRunId(), snapshot.referenceVersionId().orElseThrow().toString(),
                        NOW, NOW, BigDecimal.ZERO, BigDecimal.valueOf(120), "test", 0, "{}")));
        assertEquals(0, count(snapshot));
    }

    @Test
    void preservesNanosecondThresholdWithoutRounding() {
        var snapshot = snapshot(NOW, 60, Optional.of(1));
        var threshold = Duration.ofSeconds(60).plusNanos(1);
        var result = service.record(snapshot, threshold, FRESHNESS);
        assertEquals(0, new BigDecimal("60.000000001").compareTo(result.thresholdSeconds()));
        assertEquals(threshold, evaluation(snapshot).threshold());
        assertEquals(1, result.bunchedGroupCount());
    }

    @Test
    void demoSelectionCreatesOnlyRunScopedSimulationAlertsWithSimulationEvidence() {
        UUID demoRunId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        jdbc.update("UPDATE bunching.consumer_selection SET mode='DEMO', demo_run_id=?, changed_at=? WHERE id=1",
                demoRunId, NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("UPDATE bunching.ingestion_feed_cursor SET feed_scope=?, demo_run_id=?, last_sequence=0 WHERE id=1",
                "DEMO:" + demoRunId, demoRunId);

        service.record(simulationSnapshot(demoRunId, NOW.minusSeconds(1), 45), THRESHOLD, FRESHNESS);
        service.record(simulationSnapshot(demoRunId, NOW, 45), THRESHOLD, FRESHNESS);
        service.record(simulationSnapshot(otherRunId, 45), THRESHOLD, FRESHNESS);
        service.record(snapshot(NOW, 45, Optional.of(1)), THRESHOLD, FRESHNESS);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event "
                + "WHERE classification='SIMULATION' AND demo_run_id=?", Integer.class, demoRunId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event "
                + "WHERE demo_run_id IS DISTINCT FROM ?", Integer.class, demoRunId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_state", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.demo_alert_state WHERE demo_run_id=?",
                Integer.class, demoRunId));
        assertEquals("SIMULATION", jdbc.queryForObject("SELECT evidence->'snapshot'->>'classification' "
                + "FROM bunching.alert_event WHERE demo_run_id=?", String.class, demoRunId));
        assertEquals(demoRunId.toString(), jdbc.queryForObject("SELECT evidence->'snapshot'->>'demoRunId' "
                + "FROM bunching.alert_event WHERE demo_run_id=?", String.class, demoRunId));
    }

    @Test
    void liveSelectionIgnoresSimulationAndWritesOnlyLiveAlertState() {
        UUID demoRunId = UUID.randomUUID();

        service.record(simulationSnapshot(demoRunId, 45), THRESHOLD, FRESHNESS);
        service.record(snapshot(NOW, 45, Optional.of(1)), THRESHOLD, FRESHNESS);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event "
                + "WHERE classification='OBSERVATION_ONLY' AND demo_run_id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event "
                + "WHERE classification='SIMULATION'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_state", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.demo_alert_state", Integer.class));
    }

    private BunchingEvaluation evaluation(ArrivalSnapshot snapshot) {
        return mapper.readValue(observations.findByRawObservationId(snapshot.rawObservationId())
                .orElseThrow().evidence(), BunchingEvaluation.class);
    }

    private int count(ArrivalSnapshot snapshot) {
        return jdbc.queryForObject("SELECT count(*) FROM bunching.bunching_observation WHERE raw_observation_id = ?",
                Integer.class, snapshot.rawObservationId());
    }

    private ArrivalSnapshot snapshot(Instant fetchedAt, int gap, Optional<Integer> direction) {
        return new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), fetchedAt,
                List.of(new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", direction, 1,
                                Optional.of(NOW.plusSeconds(300))),
                        new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", direction, 2,
                                Optional.of(NOW.plusSeconds(300 + gap)))));
    }

    private ArrivalSnapshot simulationSnapshot(UUID demoRunId, int gap) {
        return simulationSnapshot(demoRunId, NOW, gap);
    }

    private ArrivalSnapshot simulationSnapshot(UUID demoRunId, Instant fetchedAt, int gap) {
        var live = snapshot(fetchedAt, gap, Optional.of(1));
        return new ArrivalSnapshot(live.rawObservationId(), live.pollRunId(), live.referenceVersionId(),
                live.fetchedAt(), live.arrivals(), "SIMULATION", demoRunId);
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
