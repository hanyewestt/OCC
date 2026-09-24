package com.csd.occ.bunching.integration;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.BunchingRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"occ.bunching.consumer-enabled=false", "occ.bunching.stale-after=120s",
        "spring.datasource.url=jdbc:postgresql://unused/test", "spring.datasource.password=synthetic-only"})
@Testcontainers(disabledWithoutDocker = true)
@Import(BunchingAlertIT.Time.class)
class BunchingAlertIT {
    private static final Instant NOW = Instant.parse("2026-09-23T04:00:00Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("db/bunching-schema.sql");
    private final BunchingRecordingService recording;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PlatformTransactionManager transactions;

    @Autowired
    BunchingAlertIT(BunchingRecordingService recording, JdbcTemplate jdbc, DataSource dataSource,
                   PlatformTransactionManager transactions) {
        this.recording = recording;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    @BeforeEach
    void reset() { jdbc.execute("TRUNCATE bunching.alert_state, bunching.alert_event, bunching.bunching_observation"); }

    @Test
    void opensOnceClearsAndReopensWithANewEpisode() {
        record(snapshot(0, 60, Optional.of(1)));
        UUID episode = jdbc.queryForObject("SELECT alert_id FROM bunching.alert_event", UUID.class);
        record(snapshot(1, 60, Optional.of(1)));
        assertEquals(1, events());
        record(snapshot(2, 90, Optional.of(1)));
        assertEquals(2, events());
        assertEquals(episode, jdbc.queryForObject("SELECT alert_id FROM bunching.alert_event WHERE action='CLEARED'", UUID.class));
        record(snapshot(3, 60, Optional.of(1)));
        assertEquals(3, events());
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT alert_id) FROM bunching.alert_event", Integer.class));
    }

    @Test
    void staleUnknownAndLateEvidenceCannotOpenOrClearAnAlert() {
        record(snapshot(-120, 60, Optional.of(1)));
        record(snapshot(0, 60, Optional.empty()));
        assertEquals(0, events());
        record(snapshot(5, 60, Optional.of(1)));
        record(snapshot(4, 180, Optional.of(1)));
        record(snapshot(5, 180, Optional.of(1)));
        record(snapshot(-120, 180, Optional.of(1)));
        assertEquals(1, events());
        assertNotNull(jdbc.queryForObject("SELECT active_alert_id FROM bunching.alert_state", UUID.class));
    }

    @Test
    void insufficientEvidencePreservesTheEpisodeAndBlocksOlderNormalEvidence() {
        record(snapshot(0, 60, Optional.of(1)));
        var source = snapshot(5, 60, Optional.of(1));
        record(new ArrivalSnapshot(source.rawObservationId(), source.pollRunId(), source.referenceVersionId(),
                source.fetchedAt(), List.of(source.arrivals().getFirst())));
        record(snapshot(3, 180, Optional.of(1)));
        assertEquals(1, events());
    }

    @Test
    void concurrentSnapshotsAndRetriesCreateOneOpening() throws Exception {
        var same = snapshot(0, 60, Optional.of(1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> record(same));
            var second = executor.submit(() -> record(same));
            var third = executor.submit(() -> record(snapshot(1, 60, Optional.of(1))));
            first.get(); second.get(); third.get();
        }
        assertEquals(1, events());
    }

    @Test
    void directionsHaveIndependentEpisodesAndRetainEvidenceAfterSourceAuditDeletion() {
        record(snapshot(0, 60, Optional.of(1)));
        record(snapshot(0, 60, Optional.of(2)));
        record(snapshot(1, 90, Optional.of(1)));
        assertEquals(3, events());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_state WHERE active_alert_id IS NOT NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT direction_id FROM bunching.alert_state WHERE active_alert_id IS NOT NULL", Integer.class));
        jdbc.execute("DELETE FROM bunching.bunching_observation");
        assertEquals(3, events());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event WHERE evidence->'snapshot' IS NOT NULL", Integer.class));
    }

    @Test
    void eachAlertRetainsOnlyItsOwnGroupEvidence() throws Exception {
        var source = new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), NOW, List.of(
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(300))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 2, Optional.of(NOW.plusSeconds(360))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "972", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(300))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "972", "44009", Optional.of(1), 2, Optional.of(NOW.plusSeconds(360)))));
        record(source);

        var evidence = jdbc.queryForList("SELECT evidence FROM bunching.alert_event", String.class);
        assertEquals(2, evidence.size());
        for (String json : evidence) {
            var decoded = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
            assertEquals(1, decoded.path("groups").size());
            assertEquals(2, decoded.path("snapshot").path("arrivals").size());
        }
    }

    @Test
    void notificationArrivesAfterCommitWithinFiveSecondsAndReferencesDurableEvidence() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("LISTEN bunching_alert");
            long started = System.nanoTime();
            record(snapshot(0, 60, Optional.of(1)));
            var notifications = connection.unwrap(PGConnection.class).getNotifications(4000);
            assertNotNull(notifications);
            assertEquals(1, notifications.length);
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
            UUID event = UUID.fromString(notifications[0].getParameter());
            assertEquals("OPENED", jdbc.queryForObject("SELECT action FROM bunching.alert_event WHERE id=?", String.class, event));
            assertEquals("OBSERVATION_ONLY", jdbc.queryForObject("SELECT classification FROM bunching.alert_event WHERE id=?", String.class, event));
        }
    }

    @Test
    void rollbackDiscardsEvaluationStateEventAndNotification() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("LISTEN bunching_alert");
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                record(snapshot(0, 60, Optional.of(1)));
                throw new IllegalStateException("synthetic rollback");
            }));
            assertEquals(0, events());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.alert_state", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.bunching_observation", Integer.class));
            var notifications = connection.unwrap(PGConnection.class).getNotifications(100);
            assertTrue(notifications == null || notifications.length == 0);
        }
    }

    @Test
    void eventInsertFailureRollsBackEvaluationAndCanBeRetried() {
        var input = snapshot(0, 60, Optional.of(1));
        jdbc.execute("ALTER TABLE bunching.alert_event ADD CONSTRAINT test_reject_event CHECK(false) NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> record(input));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.bunching_observation", Integer.class));
        } finally { jdbc.execute("ALTER TABLE bunching.alert_event DROP CONSTRAINT test_reject_event"); }
        record(input);
        assertEquals(1, events());
    }

    private void record(ArrivalSnapshot snapshot) {
        recording.record(snapshot, Duration.ofSeconds(90), Duration.ofSeconds(120));
    }

    private int events() { return jdbc.queryForObject("SELECT count(*) FROM bunching.alert_event", Integer.class); }

    private ArrivalSnapshot snapshot(int offset, int gap, Optional<Integer> direction) {
        return new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), NOW.plusSeconds(offset), List.of(
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", direction, 1, Optional.of(NOW.plusSeconds(300))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", direction, 2, Optional.of(NOW.plusSeconds(300 + gap)))));
    }

    @TestConfiguration
    static class Time {
        @Bean @Primary Clock alertClock() { return Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC); }
    }
}
