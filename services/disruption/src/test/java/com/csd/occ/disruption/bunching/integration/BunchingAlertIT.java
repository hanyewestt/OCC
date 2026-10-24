package com.csd.occ.disruption.bunching.integration;

import com.csd.occ.disruption.bunching.domain.ArrivalSnapshot;
import com.csd.occ.disruption.bunching.domain.BunchingRecordingService;
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
import org.springframework.test.context.ActiveProfiles;
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

@ActiveProfiles("test")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties = {"occ.disruption.consumer-enabled=false", "occ.disruption.planning.worker-enabled=false",
        "occ.disruption.stale-after=120s",
        "spring.datasource.url=jdbc:postgresql://unused/test", "spring.datasource.password=synthetic-only"})
@Testcontainers(disabledWithoutDocker = true)
@Import(BunchingAlertIT.Time.class)
class BunchingAlertIT {
    private static final Instant NOW = Instant.parse("2026-09-23T04:00:00Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("db/disruption-schema.sql");
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

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "occ.exportOpenapi", matches = "true")
    void exportsGeneratedContract() throws Exception {
        String contract = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("http://localhost:6768/v3/api-docs").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.nio.file.Path output = java.nio.file.Path.of("openapi", "disruption.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, contract);
    }

    @BeforeEach
    void reset() { jdbc.update("UPDATE disruption.consumer_state SET mode='LIVE', demo_run_id=null, updated_at=?", java.sql.Timestamp.from(NOW.minusSeconds(3600))); jdbc.execute("TRUNCATE disruption.planning_outbox, disruption.event_evidence, disruption.disruption_event, disruption.traffic_incident_state, disruption.disruption_processing_state, disruption.disruption, disruption.source_processing_state"); }

    @Test
    void opensOnceClearsAndReopensWithANewEpisode() {
        record(snapshot(0, 60, Optional.of(1)));
        assertEquals(0, events());
        record(snapshot(1, 60, Optional.of(1)));
        UUID episode = jdbc.queryForObject("SELECT disruption_id FROM disruption.disruption_event", UUID.class);
        assertEquals(1, events());
        record(snapshot(2, 90, Optional.of(1)));
        assertEquals(2, events());
        assertEquals(episode, jdbc.queryForObject("SELECT disruption_id FROM disruption.disruption_event WHERE action='CLEARED'", UUID.class));
        record(snapshot(3, 60, Optional.of(1)));
        record(snapshot(4, 60, Optional.of(1)));
        assertEquals(3, events());
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT disruption_id) FROM disruption.disruption_event", Integer.class));
    }

    @Test
    void staleEvidenceCannotOpenAndMissingDirectionOpensAnUnspecifiedDirectionEpisode() {
        record(snapshot(-120, 60, Optional.of(1)));
        record(snapshot(0, 60, Optional.empty()));
        assertEquals(1, events());
        assertEquals(0, jdbc.queryForObject("SELECT direction_id FROM disruption.disruption_processing_state", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT active_disruption_id FROM disruption.disruption_processing_state", UUID.class));
    }

    @Test
    void insufficientEvidencePreservesTheEpisodeAndBlocksOlderNormalEvidence() {
        record(snapshot(0, 60, Optional.of(1)));
        record(snapshot(1, 60, Optional.of(1)));
        var source = snapshot(5, 60, Optional.of(1));
        record(new ArrivalSnapshot(source.rawObservationId(), source.pollRunId(), source.referenceVersionId(),
                source.fetchedAt(), List.of(source.arrivals().getFirst())));
        record(snapshot(3, 180, Optional.of(1)));
        assertEquals(1, events());
    }

    @Test
    void concurrentSnapshotsAndRetriesCreateOneOpening() throws Exception {
        record(snapshot(-1, 60, Optional.of(1)));
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
        record(snapshot(1, 60, Optional.of(1)));
        record(snapshot(1, 60, Optional.of(2)));
        record(snapshot(2, 90, Optional.of(1)));
        assertEquals(3, events());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM disruption.disruption_processing_state WHERE active_disruption_id IS NOT NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT direction_id FROM disruption.disruption_processing_state WHERE active_disruption_id IS NOT NULL", Integer.class));
        jdbc.execute("DELETE FROM disruption.source_processing_state");
        assertEquals(3, events());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM disruption.disruption_event WHERE evidence->'snapshot' IS NOT NULL", Integer.class));
    }

    @Test
    void eachAlertRetainsOnlyItsOwnGroupEvidence() throws Exception {
        var source = new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), NOW, List.of(
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(300))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 2, Optional.of(NOW.plusSeconds(360))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "972", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(300))),
                new ArrivalSnapshot.Arrival(UUID.randomUUID(), "972", "44009", Optional.of(1), 2, Optional.of(NOW.plusSeconds(360)))));
        record(source);
        record(new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), NOW.plusSeconds(1),
                source.arrivals().stream().map(arrival -> new ArrivalSnapshot.Arrival(UUID.randomUUID(),
                        arrival.serviceId(), arrival.stopCode(), arrival.directionId(), arrival.visitOrder(),
                        arrival.estimatedArrivalAt())).toList()));

        var evidence = jdbc.queryForList("SELECT evidence FROM disruption.disruption_event", String.class);
        assertEquals(2, evidence.size());
        for (String json : evidence) {
            var decoded = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
            assertEquals(1, decoded.path("groups").size());
            assertEquals(2, decoded.path("snapshot").path("arrivals").size());
        }
    }

    @Test
    void notificationArrivesAfterCommitWithinFiveSecondsAndReferencesDurableEvidence() throws Exception {
        record(snapshot(-1, 60, Optional.of(1)));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("LISTEN bunching_alert");
            long started = System.nanoTime();
            record(snapshot(0, 60, Optional.of(1)));
            var notifications = connection.unwrap(PGConnection.class).getNotifications(4000);
            assertNotNull(notifications);
            assertEquals(1, notifications.length);
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
            UUID event = UUID.fromString(notifications[0].getParameter());
            assertEquals("OPENED", jdbc.queryForObject("SELECT action FROM disruption.disruption_event WHERE id=?", String.class, event));
            assertEquals("OBSERVATION_ONLY", jdbc.queryForObject("SELECT classification FROM disruption.disruption_event WHERE id=?", String.class, event));
            statement.execute("UNLISTEN *");
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
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM disruption.disruption_processing_state", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM disruption.source_processing_state", Integer.class));
            var notifications = connection.unwrap(PGConnection.class).getNotifications(100);
            assertTrue(notifications == null || notifications.length == 0);
            statement.execute("UNLISTEN *");
        }
    }

    @Test
    void eventInsertFailureRollsBackEvaluationAndCanBeRetried() {
        record(snapshot(-1, 60, Optional.of(1)));
        var input = snapshot(0, 60, Optional.of(1));
        jdbc.execute("ALTER TABLE disruption.disruption_event ADD CONSTRAINT test_reject_event CHECK(false) NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> record(input));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM disruption.source_processing_state", Integer.class));
        } finally { jdbc.execute("ALTER TABLE disruption.disruption_event DROP CONSTRAINT test_reject_event"); }
        record(input);
        assertEquals(1, events());
    }

    @Test
    void confirmedLiveEventQueuesPlanningOnceWithRetainedObservationOnlyEvidence() {
        record(snapshot(0, 60, Optional.of(1)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM disruption.planning_outbox", Integer.class));
        var second = snapshot(1, 60, Optional.of(1));
        record(second);
        record(second);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM disruption.planning_outbox", Integer.class));
        assertEquals("OBSERVATION_ONLY", jdbc.queryForObject(
                "SELECT payload_json::jsonb ->> 'classification' FROM disruption.planning_outbox", String.class));
    }

    private void record(ArrivalSnapshot snapshot) {
        recording.record(snapshot, Duration.ofSeconds(90), Duration.ofSeconds(120));
    }

    private int events() { return jdbc.queryForObject("SELECT count(*) FROM disruption.disruption_event", Integer.class); }

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
