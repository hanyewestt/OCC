package com.csd.occ.bunching.integration;

import com.csd.occ.bunching.domain.BunchingConsumptionService;
import com.csd.occ.bunching.domain.BunchingEvaluation;
import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.StoredArrivalPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "occ.bunching.consumer-enabled=false", "occ.bunching.stale-after=120s",
        "spring.datasource.url=jdbc:postgresql://unused/test", "spring.datasource.password=synthetic-test-only",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
@Import(StoredArrivalDetectionIT.ControlledTime.class)
class StoredArrivalDetectionIT {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final UUID REFERENCE = new UUID(0, 100);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withInitScript("db/ingestion-schema.sql");

    @DynamicPropertySource
    static void migrateIngestion(DynamicPropertyRegistry registry) throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("ingestion").locations("classpath:ingestion-migration").load().migrate();
        for (String script : new String[]{"02_bunching_schema.sh", "03_bunching_read_grants.sh"}) {
            POSTGRES.copyFileToContainer(MountableFile.forClasspathResource("local-init/" + script), "/tmp/" + script);
            var result = POSTGRES.execInContainer("env", "POSTGRES_USER=" + POSTGRES.getUsername(),
                    "POSTGRES_DB=" + POSTGRES.getDatabaseName(), "OCC_BUNCHING_DB_PASSWORD=synthetic-test-only",
                    "bash", "/tmp/" + script);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
    }

    private final JdbcTemplate jdbc;
    private final BunchingConsumptionService consumer;
    private final BunchingObservationRepository observations;
    private final JsonMapper mapper;
    private final DataSource dataSource;
    private final ControlledClock clock;
    private final StoredArrivalPort source;

    @Autowired
    StoredArrivalDetectionIT(JdbcTemplate jdbc, BunchingConsumptionService consumer,
                             BunchingObservationRepository observations, JsonMapper mapper, DataSource dataSource,
                             ControlledClock clock, StoredArrivalPort source) {
        this.jdbc = jdbc;
        this.consumer = consumer;
        this.observations = observations;
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.clock = clock;
        this.source = source;
    }

    @BeforeEach
    void resetSyntheticRows() {
        clock.set(NOW);
        jdbc.execute("TRUNCATE bunching.alert_state, bunching.alert_event");
        jdbc.execute("TRUNCATE bunching.bunching_observation, bunching.source_rejection, bunching.source_retry, bunching.source_processing_audit");
        jdbc.execute("TRUNCATE ingestion.poll_run, ingestion.reference_dataset_version CASCADE");
    }

    @Test
    void scheduledStoredObservationProducesNotificationWithinFiveSeconds() throws Exception {
        seedTopology();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("LISTEN bunching_alert");
            var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            scheduler.initialize();
            try {
                var settings = new com.csd.occ.bunching.infrastructure.config.BunchingProperties(
                        Duration.ofSeconds(90), Duration.ofSeconds(120), Duration.ofSeconds(1), 100);
                var schedule = new com.csd.occ.bunching.infrastructure.scheduling.BunchingSchedule(consumer, settings);
                UUID sourceId = insertRaw("SUCCESS", payload(60), NOW);
                long started = System.nanoTime();
                scheduler.scheduleWithFixedDelay(schedule::consumeStoredArrivals,
                        scheduler.getClock().instant().plus(settings.pollInterval()), settings.pollInterval());
                var notifications = connection.unwrap(org.postgresql.PGConnection.class).getNotifications(4500);
                assertNotNull(notifications);
                assertEquals(1, notifications.length);
                assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
                assertEquals(sourceId, jdbc.queryForObject("SELECT raw_observation_id FROM bunching.alert_event", UUID.class));
            } finally { scheduler.shutdown(); }
        }
    }

    @ParameterizedTest
    @CsvSource({"89,BUNCHING", "90,NORMAL", "91,NORMAL"})
    void storedRawResponseFlowsThroughPolicyAndAudit(int gap, BunchingEvaluation.State state) {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(gap), NOW);
        var result = consume();
        assertEquals(1, result.recorded());
        assertTrue(result.failedObservationIds().isEmpty());
        var evaluation = evaluation(id);
        assertEquals(state, evaluation.groups().getFirst().state());
        assertEquals(Duration.ofSeconds(gap), evaluation.groups().getFirst().headways().getFirst().gap());
        assertEquals(id, evaluation.snapshot().rawObservationId());
        assertEquals(REFERENCE, evaluation.snapshot().referenceVersionId().orElseThrow());
        assertEquals(0, consume().recorded());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.bunching_observation", Integer.class));
    }

    @Test
    void prefersPersistedNormalizedRowsWhenAvailable() {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(180), NOW);
        for (int slot = 1; slot <= 2; slot++) {
            jdbc.update("""
                    INSERT INTO ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, stop_code, service_id,
                     direction_id, visit_order, estimated_arrival_at, fetched_at, ingested_at, record_checksum)
                    VALUES (?, ?, ?, ?, '44009', '190', 1, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), id, id, REFERENCE, slot,
                    Timestamp.from(NOW.plusSeconds(slot == 1 ? 300 : 360)), Timestamp.from(NOW), Timestamp.from(NOW), "a".repeat(64));
        }
        assertEquals(1, consume().recorded());
        assertEquals(Duration.ofSeconds(60), evaluation(id).groups().getFirst().headways().getFirst().gap());
    }

    @Test
    void incompleteNormalizedBatchRetriesAfterTheRemainingRowsCommit() {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(60), NOW);
        for (int slot = 1; slot <= 2; slot++) {
            jdbc.update("""
                    INSERT INTO ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, stop_code, service_id,
                     direction_id, visit_order, estimated_arrival_at, fetched_at, ingested_at, record_checksum)
                    VALUES (?, ?, ?, ?, '44009', '190', 1, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), id, id, REFERENCE, slot,
                    Timestamp.from(NOW.plusSeconds(slot == 1 ? 300 : 360)), Timestamp.from(NOW), Timestamp.from(NOW), "a".repeat(64));
            if (slot == 1) {
                var partial = consume();
                assertEquals(java.util.List.of(id), partial.failedObservationIds());
                assertEquals(0, partial.rejected());
                assertTrue(observations.findByRawObservationId(id).isEmpty());
            }
        }
        assertEquals(0, consume().recorded());
        clock.set(NOW.plusSeconds(30).minusNanos(1000));
        assertEquals(0, consume().recorded());
        clock.set(NOW.plusSeconds(30));
        assertEquals(1, consume().recorded());
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation(id).groups().getFirst().state());
    }

    @Test
    void fullBatchOfIncompleteInputsDoesNotStarveNewerValidObservations() {
        seedTopology();
        var incomplete = new ArrayList<UUID>();
        for (int index = 0; index < 100; index++) {
            UUID id = insertRaw("SUCCESS", payload(60), NOW.minusSeconds(1));
            insertNormalized(id, 1, NOW.minusSeconds(1));
            incomplete.add(id);
        }
        UUID good = insertRaw("SUCCESS", payload(60), NOW);
        var first = consume();
        assertEquals(100, first.failedObservationIds().size());
        assertEquals(0, first.recorded());
        assertEquals(100, jdbc.queryForObject("SELECT count(*) FROM bunching.source_retry", Integer.class));

        // Persisted deadlines survive reconstruction of the adapter (no in-memory queue state).
        var restarted = new com.csd.occ.bunching.infrastructure.ingestion.PostgresStoredArrivalAdapter(jdbc,
                new com.csd.occ.bunching.infrastructure.ingestion.StoredArrivalMapper(mapper));
        assertEquals(java.util.List.of(good), restarted.pendingIds(100, NOW));
        var second = consume();
        assertEquals(1, second.recorded());
        assertTrue(second.failedObservationIds().isEmpty());
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation(good).groups().getFirst().state());

        // Even when failures are due again, waiting fresh observations retain their queue position.
        UUID later = insertRaw("SUCCESS", payload(60), NOW.plusSeconds(5));
        clock.set(NOW.plusSeconds(30));
        assertEquals(later, source.pendingIds(100, clock.instant()).getFirst());
        assertEquals(1, consume().recorded());
        assertTrue(observations.findByRawObservationId(later).isPresent());
    }

    @Test
    void concurrentDeferralsCannotShortenThePersistedCooldown() {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(60), NOW);
        source.defer(id, NOW, NOW.plusSeconds(60), StoredArrivalPort.RetryReason.PROCESSING_FAILED);
        source.defer(id, NOW, NOW.plusSeconds(30), StoredArrivalPort.RetryReason.PROCESSING_FAILED);
        assertTrue(source.pendingIds(100, NOW.plusSeconds(59)).isEmpty());
        assertEquals(java.util.List.of(id), source.pendingIds(100, NOW.plusSeconds(60)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM bunching.source_processing_audit", Integer.class));
        assertEquals(NOW.plusSeconds(60), jdbc.queryForObject(
                "SELECT min(next_attempt_at) FROM bunching.source_processing_audit", java.sql.Timestamp.class).toInstant());
    }

    @Test
    void normalizedMissingMiddleSlotIsRejectedWithoutBunching() {
        seedTopology();
        var json = mapper.readTree(payload(60));
        var service = (tools.jackson.databind.node.ObjectNode) json.path("Services").get(0);
        service.set("NextBus3", service.path("NextBus2"));
        service.set("NextBus2", mapper.createObjectNode().put("EstimatedArrival", ""));
        UUID id = insertRaw("SUCCESS", mapper.writeValueAsString(json), NOW);
        insertNormalized(id, 1, NOW);
        insertNormalized(id, 3, NOW);
        var result = consume();
        assertEquals(1, result.rejected());
        assertEquals(0, result.recorded());
        assertTrue(observations.findByRawObservationId(id).isEmpty());
        assertEquals("INCOMPLETE_ARRIVAL_SEQUENCE", jdbc.queryForObject(
                "SELECT reason FROM bunching.source_rejection WHERE raw_observation_id = ?", String.class, id));
    }

    @Test
    void newThresholdAppliesToNewObservationsWithoutChangingHistory() {
        seedTopology();
        UUID first = insertRaw("SUCCESS", payload(60), NOW);
        consume();
        UUID second = insertRaw("SUCCESS", payload(60), NOW);
        assertEquals(1, consumer.consume(100, Duration.ofSeconds(45), Duration.ofSeconds(120)).recorded());
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation(first).groups().getFirst().state());
        assertEquals(BunchingEvaluation.State.NORMAL, evaluation(second).groups().getFirst().state());
        assertEquals(Duration.ofSeconds(45), evaluation(second).threshold());
    }

    @Test
    void unknownTopologyIsRecordedWithoutInventingDirection() {
        UUID id = insertRaw("SUCCESS", payload(60), NOW);
        assertEquals(1, consume().recorded());
        assertTrue(evaluation(id).snapshot().referenceVersionId().isEmpty());
        assertEquals(BunchingEvaluation.Reason.UNKNOWN_DIRECTION, evaluation(id).groups().getFirst().reason());
    }

    @Test
    void malformedObservationDoesNotBlockTheNextSnapshot() {
        seedTopology();
        UUID bad = insertRaw("SUCCESS", "{\"BusStopCode\":\"44009\",\"Services\":\"broken\"}", NOW.minusSeconds(1));
        UUID good = insertRaw("SUCCESS", payload(60), NOW);
        var result = consume();
        assertEquals(1, result.rejected());
        assertEquals(1, result.recorded());
        assertTrue(observations.findByRawObservationId(bad).isEmpty());
        assertEquals("INVALID_SERVICES", jdbc.queryForObject(
                "SELECT reason FROM bunching.source_rejection WHERE raw_observation_id = ?", String.class, bad));
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation(good).groups().getFirst().state());
        assertEquals(0, consume().rejected());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bunching.source_processing_audit", Integer.class));
        assertEquals(bad, jdbc.queryForObject("SELECT poll_run_id FROM bunching.source_processing_audit", UUID.class));
        jdbc.update("DELETE FROM ingestion.raw_observation WHERE id = ?", bad);
        jdbc.update("DELETE FROM ingestion.poll_run WHERE id = ?", bad);
        assertEquals("INVALID_SERVICES", jdbc.queryForObject("SELECT reason FROM bunching.source_processing_audit", String.class));
    }

    @Test
    void processingAuditFailureRollsBackQuarantineAndRetryState() {
        UUID id = insertRaw("SUCCESS", payload(60), NOW);
        jdbc.execute("ALTER TABLE bunching.source_processing_audit ADD CONSTRAINT test_fail_audit CHECK (false) NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> source.reject(id, NOW, "INVALID_SERVICES"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.source_rejection", Integer.class));
            assertThrows(RuntimeException.class, () -> source.defer(id, NOW, NOW.plusSeconds(30), StoredArrivalPort.RetryReason.PROCESSING_FAILED));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bunching.source_retry", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE bunching.source_processing_audit DROP CONSTRAINT test_fail_audit");
        }
    }

    @Test
    void missingSourceStillLeavesAnAuditOfTheFailedAttempt() {
        UUID removed = UUID.randomUUID();
        source.defer(removed, NOW, NOW.plusSeconds(30), StoredArrivalPort.RetryReason.PROCESSING_FAILED);
        assertEquals(removed, jdbc.queryForObject("SELECT raw_observation_id FROM bunching.source_processing_audit", UUID.class));
        assertNull(jdbc.queryForObject("SELECT poll_run_id FROM bunching.source_processing_audit", UUID.class));
    }

    @Test
    void failedAndRunningPollsAreExcludedButLaterSuccessIsDiscovered() {
        seedTopology();
        UUID late = insertRaw("RUNNING", payload(60), NOW.minusSeconds(10));
        UUID failed = insertRaw("FAILED", payload(60), NOW);
        UUID current = insertRaw("SUCCESS", payload(60), NOW);
        assertEquals(1, consume().recorded());
        assertTrue(observations.findByRawObservationId(failed).isEmpty());
        assertTrue(observations.findByRawObservationId(late).isEmpty());
        assertTrue(observations.findByRawObservationId(current).isPresent());
        jdbc.update("UPDATE ingestion.poll_run SET outcome = 'SUCCESS' WHERE id = ?", late);
        assertEquals(1, consume().recorded());
        assertTrue(observations.findByRawObservationId(late).isPresent());
    }

    @Test
    void staleInputsKeepTheirOriginalFetchTimeAndDoNotBecomeBunching() {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(60), NOW.minusSeconds(120));
        assertEquals(1, consume().recorded());
        assertEquals(NOW.minusSeconds(120), evaluation(id).snapshot().fetchedAt());
        assertEquals(BunchingEvaluation.Reason.STALE_SNAPSHOT, evaluation(id).groups().getFirst().reason());
    }

    @Test
    void retainedEvidenceSurvivesSourceTelemetryDeletion() {
        seedTopology();
        UUID id = insertRaw("SUCCESS", payload(60), NOW);
        consume();
        var before = evaluation(id);
        jdbc.update("DELETE FROM ingestion.raw_observation WHERE id = ?", id);
        jdbc.update("DELETE FROM ingestion.poll_run WHERE id = ?", id);
        assertEquals(before, evaluation(id));
    }

    @Test
    void grantsAllowReadViewsButDenyAccessToUnderlyingIngestionTables() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("SET ROLE occ_bunching");
                assertDoesNotThrow(() -> statement.executeQuery("SELECT * FROM ingestion.bunching_snapshot"));
                assertDoesNotThrow(() -> statement.executeQuery("SELECT * FROM ingestion.bunching_route_direction"));
                assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT * FROM ingestion.raw_observation"));
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("DELETE FROM ingestion.raw_observation"));
            } finally {
                statement.execute("RESET ROLE");
            }
        }
    }

    private BunchingConsumptionService.BatchResult consume() {
        return consumer.consume(100, Duration.ofSeconds(90), Duration.ofSeconds(120));
    }

    private BunchingEvaluation evaluation(UUID id) {
        return mapper.readValue(observations.findByRawObservationId(id).orElseThrow().evidence(), BunchingEvaluation.class);
    }

    private UUID insertRaw(String outcome, String payload, Instant fetchedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ingestion.poll_run (id, source, scope_key, idempotency_key, started_at, outcome)
                VALUES (?, 'DATAMALL', 'stop:44009', ?, ?, ?)
                """, id, id.toString(), Timestamp.from(fetchedAt), outcome);
        jdbc.update("""
                INSERT INTO ingestion.raw_observation
                (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                 fetched_at, ingested_at, http_status, classification)
                VALUES (?, ?, 'DATAMALL', 'stop:44009', '{}', CAST(? AS jsonb), ?, ?, ?, 200, 'OBSERVATION_ONLY')
                """, id, id, payload, "a".repeat(64), Timestamp.from(fetchedAt), Timestamp.from(fetchedAt));
        return id;
    }

    private void insertNormalized(UUID id, int slot, Instant fetchedAt) {
        jdbc.update("""
                INSERT INTO ingestion.bus_arrival_observation
                (id, raw_observation_id, poll_run_id, reference_version_id, stop_code, service_id,
                 direction_id, visit_order, estimated_arrival_at, fetched_at, ingested_at, record_checksum)
                VALUES (?, ?, ?, ?, '44009', '190', 1, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, id, REFERENCE, slot,
                Timestamp.from(NOW.plusSeconds(slot == 1 ? 300 : 360)),
                Timestamp.from(fetchedAt), Timestamp.from(fetchedAt), "a".repeat(64));
    }

    private void seedTopology() {
        jdbc.update("""
                INSERT INTO ingestion.reference_dataset_version (id, dataset, version_key, checksum, status, imported_at)
                VALUES (?, 'BUS_ROUTES', 'fixture', ?, 'ACTIVE', ?)
                """, REFERENCE, "a".repeat(64), Timestamp.from(NOW));
        jdbc.update("INSERT INTO ingestion.bus_service VALUES (?, '190', 'SMRT', 'TRUNK', ?)", REFERENCE, Timestamp.from(NOW));
        for (String stop : new String[]{"44009", "10009"}) {
            jdbc.update("INSERT INTO ingestion.bus_stop VALUES (?, ?, 'Synthetic road', 'Synthetic stop', ST_SetSRID(ST_Point(103.8, 1.3), 4326), ?)",
                    REFERENCE, stop, Timestamp.from(NOW));
        }
        jdbc.update("INSERT INTO ingestion.bus_route_stop VALUES (?, '190', 'SMRT', 1, 1, '44009', 0, ?)", REFERENCE, Timestamp.from(NOW));
        jdbc.update("INSERT INTO ingestion.bus_route_stop VALUES (?, '190', 'SMRT', 1, 2, '10009', 1, ?)", REFERENCE, Timestamp.from(NOW));
    }

    private String payload(int gap) {
        return """
                {"BusStopCode":"44009","Services":[{"ServiceNo":"190","Operator":"SMRT",
                "NextBus":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"1","EstimatedArrival":"%s"},
                "NextBus2":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"1","EstimatedArrival":"%s"},
                "NextBus3":{"EstimatedArrival":""}}]}
                """.formatted(NOW.plusSeconds(300), NOW.plusSeconds(300 + gap));
    }

    @TestConfiguration
    static class ControlledTime {
        @Bean
        @Primary
        ControlledClock testClock() { return new ControlledClock(); }
    }

    // Mutable only in tests, so retry deadlines can be exercised without wall-clock sleeps.
    static class ControlledClock extends Clock {
        private Instant now = NOW;
        void set(Instant value) { now = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
