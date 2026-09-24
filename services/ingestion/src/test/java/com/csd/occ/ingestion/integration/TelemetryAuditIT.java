package com.csd.occ.ingestion.integration;

import com.csd.occ.ingestion.domain.*;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallArrivalFeedAdapter;
import com.csd.occ.ingestion.infrastructure.postgres.PostgresArrivalTelemetryAdapter;
import com.csd.occ.bunching.domain.*;
import com.csd.occ.bunching.infrastructure.ingestion.PostgresStoredArrivalAdapter;
import com.csd.occ.bunching.infrastructure.ingestion.StoredArrivalMapper;
import com.csd.occ.bunching.infrastructure.jackson.JacksonBunchingEvidenceAdapter;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TelemetryAuditIT.Application.class, properties = {
        "spring.main.web-application-type=none", "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.datasource.password=synthetic-only",
        "occ.ingestion.arrival.account-key=synthetic-secret", "occ.ingestion.arrival.timeout=1s",
        "occ.ingestion.arrival.lease=45s", "occ.ingestion.arrival.max-response-bytes=4096",
        "occ.ingestion.arrival.stale-after-seconds=120"
})
@Testcontainers(disabledWithoutDocker = true)
class TelemetryAuditIT {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final ArrivalPollTarget TARGET = new ArrivalPollTarget("DATAMALL", "stop:44009", "44009");
    private static final AtomicReference<StubResponse> RESPONSE = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> ACCOUNT_KEY = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withInitScript("db/audit-schema.sql");

    @DynamicPropertySource
    static void setup(DynamicPropertyRegistry registry) {
        for (String schema : new String[]{"ingestion", "bunching"}) {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).locations(schema.equals("ingestion") ? "classpath:db/migration" : "classpath:bunching-migration")
                    .load().migrate();
        }
        registry.add("occ.ingestion.arrival.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private final JdbcTemplate jdbc;
    private final ArrivalPollingService polling;
    private final ArrivalTelemetryPort telemetry;
    private final ArrivalFeedPort feed;
    private final BunchingConsumptionService consumer;
    private final BunchingObservationRepository observations;
    private final JsonMapper mapper;
    private final ControlledClock clock;

    @Autowired
    TelemetryAuditIT(JdbcTemplate jdbc, ArrivalPollingService polling, ArrivalTelemetryPort telemetry,
                     ArrivalFeedPort feed, BunchingConsumptionService consumer, BunchingObservationRepository observations,
                     JsonMapper mapper, ControlledClock clock) {
        this.jdbc = jdbc;
        this.polling = polling;
        this.telemetry = telemetry;
        this.feed = feed;
        this.consumer = consumer;
        this.observations = observations;
        this.mapper = mapper;
        this.clock = clock;
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE bunching.alert_state, bunching.alert_event");
        clock.now = NOW;
        REQUESTS.set(0);
        RESPONSE.set(new StubResponse(200, payload(), 0));
        jdbc.execute("TRUNCATE ingestion.poll_run, ingestion.reference_dataset_version, ingestion.feed_status, ingestion.telemetry_audit CASCADE");
        jdbc.execute("TRUNCATE bunching.bunching_observation, bunching.source_retry, bunching.source_rejection, bunching.source_processing_audit");
    }

    @AfterAll
    static void stopServer() { SERVER.stop(0); }

    @Test
    void mockHttpResponseFlowsThroughRealPollingDatabaseDetectionAndRetainedAudit() {
        seedTopology();
        var completion = poll();
        assertTrue(completion.committed());
        assertEquals("synthetic-secret", ACCOUNT_KEY.get());
        assertEquals(1, consume().recorded());
        assertEquals(1, count("bunching.alert_event"));
        UUID rawId = jdbc.queryForObject("SELECT id FROM ingestion.raw_observation", UUID.class);
        var audit = observations.findByRawObservationId(rawId).orElseThrow();
        var evaluation = mapper.readValue(audit.evidence(), BunchingEvaluation.class);
        assertEquals(completion.poll().id(), evaluation.snapshot().pollRunId());
        assertEquals(BunchingEvaluation.State.BUNCHING, evaluation.groups().getFirst().state());
        assertEquals(Duration.ofSeconds(60), evaluation.groups().getFirst().headways().getFirst().gap());
        assertEquals(2, count("ingestion.telemetry_audit"));
        assertEquals(completion.fetch().checksum().orElseThrow(), jdbc.queryForObject(
                "SELECT payload_checksum FROM ingestion.telemetry_audit WHERE action='POLL_COMPLETED'", String.class));
        assertEquals(rawId, jdbc.queryForObject(
                "SELECT raw_observation_id FROM ingestion.telemetry_audit WHERE action='POLL_COMPLETED'", UUID.class));
        assertEquals("SYSTEM:BUS_BUNCHING_DETECTION", jdbc.queryForObject(
                "SELECT actor FROM bunching.bunching_observation", String.class));
        assertEquals("HEADWAY_EVALUATED", jdbc.queryForObject("SELECT action FROM bunching.bunching_observation", String.class));
        assertTrue(polling.poll(TARGET, Duration.ofSeconds(45), 120).isEmpty());
        assertEquals(1, REQUESTS.get());
        assertEquals(0, consume().recorded());
        jdbc.execute("DELETE FROM ingestion.raw_observation");
        jdbc.execute("DELETE FROM ingestion.poll_run");
        assertEquals(2, count("ingestion.telemetry_audit"));
        assertEquals(audit.evidence(), observations.findByRawObservationId(rawId).orElseThrow().evidence());
        assertEquals(1, count("bunching.alert_event"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 302})
    void upstreamFailuresAreAuditedWithoutErasingLastSuccessOrLeakingResponseBodies(int status) {
        poll();
        clock.now = NOW.plusSeconds(60);
        RESPONSE.set(new StubResponse(status, "synthetic-secret reflected upstream error", 0));
        var failed = poll();
        assertEquals(ArrivalFetch.Reason.HTTP_ERROR, failed.fetch().reason());
        assertEquals(1, count("ingestion.raw_observation"));
        assertEquals("DEGRADED", jdbc.queryForObject("SELECT status FROM ingestion.feed_status", String.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT last_success_at FROM ingestion.feed_status", Timestamp.class).toInstant());
        assertEquals(1, jdbc.queryForObject("SELECT consecutive_failures FROM ingestion.feed_status", Integer.class));
        assertEquals(status, jdbc.queryForObject("SELECT http_status FROM ingestion.poll_run WHERE id=?", Integer.class, failed.poll().id()));
        assertEquals(4, count("ingestion.telemetry_audit"));
        assertFalse(jdbc.queryForList("SELECT row_to_json(a)::text FROM ingestion.telemetry_audit a", String.class)
                .toString().contains("synthetic-secret"));
        clock.now = NOW.plusSeconds(120);
        RESPONSE.set(new StubResponse(200, payload(), 0));
        poll();
        assertEquals("HEALTHY", jdbc.queryForObject("SELECT status FROM ingestion.feed_status", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT consecutive_failures FROM ingestion.feed_status", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json synthetic-secret", "{}", "{\"BusStopCode\":\"44009\",\"Services\":{}}",
            "{\"BusStopCode\":\"10009\",\"Services\":[]}", "{\"BusStopCode\":\"44009\",\"Services\":[]} {}"})
    void malformedResponsesProduceSafeFailureAuditAndNoDetectionInput(String body) {
        RESPONSE.set(new StubResponse(200, body, 0));
        assertEquals(ArrivalFetch.Reason.INVALID_RESPONSE, poll().fetch().reason());
        assertEquals(0, count("ingestion.raw_observation"));
        assertEquals(0, consume().recorded());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ingestion.feed_status", String.class));
        assertEquals("INVALID_RESPONSE", jdbc.queryForObject("SELECT reason FROM ingestion.telemetry_audit WHERE action='POLL_COMPLETED'", String.class));
    }

    @Test
    void emptyValidResponseIsSuccessfulAndAuditedWithZeroRecords() {
        RESPONSE.set(new StubResponse(200, "{\"BusStopCode\":\"44009\",\"Services\":[]}", 0));
        assertTrue(poll().fetch().isSuccessful());
        assertEquals(0, jdbc.queryForObject("SELECT records_received FROM ingestion.poll_run", Integer.class));
        assertEquals(1, count("ingestion.raw_observation"));
        assertEquals(1, consume().recorded());
    }

    @Test
    void detectorRejectionRemainsCorrelatedToTheOriginalHttpPollAfterTelemetryDeletion() {
        var json = mapper.readTree(payload());
        var service = (tools.jackson.databind.node.ObjectNode) json.path("Services").get(0);
        service.set("NextBus3", service.path("NextBus2"));
        service.set("NextBus2", mapper.createObjectNode().put("EstimatedArrival", ""));
        RESPONSE.set(new StubResponse(200, mapper.writeValueAsString(json), 0));
        var completion = poll();
        assertEquals(1, consume().rejected());
        assertEquals(0, count("bunching.bunching_observation"));
        assertEquals(completion.poll().id(), jdbc.queryForObject(
                "SELECT poll_run_id FROM bunching.source_processing_audit", UUID.class));
        assertEquals("SOURCE_REJECTED", jdbc.queryForObject(
                "SELECT action FROM bunching.source_processing_audit", String.class));
        assertEquals("INCOMPLETE_ARRIVAL_SEQUENCE", jdbc.queryForObject(
                "SELECT reason FROM bunching.source_processing_audit", String.class));
        jdbc.execute("DELETE FROM ingestion.raw_observation");
        jdbc.execute("DELETE FROM ingestion.poll_run");
        assertEquals(2, count("ingestion.telemetry_audit"));
        assertEquals(1, count("bunching.source_processing_audit"));
    }

    @Test
    void responseTimeoutIsRecordedAsTransportFailure() {
        RESPONSE.set(new StubResponse(200, payload(), 2000));
        assertEquals(ArrivalFetch.Reason.TRANSPORT_ERROR, poll().fetch().reason());
        assertEquals(0, count("ingestion.raw_observation"));
        assertEquals(2, count("ingestion.telemetry_audit"));
    }

    @Test
    void oversizedResponseIsBoundedAndAuditedAsTransportFailure() {
        RESPONSE.set(new StubResponse(200, "x".repeat(5000), 0));
        assertEquals(ArrivalFetch.Reason.TRANSPORT_ERROR, poll().fetch().reason());
        assertEquals(0, count("ingestion.raw_observation"));
        assertEquals(2, count("ingestion.telemetry_audit"));
    }

    @Test
    void auditFailureRollsBackRawOutcomeAndHealthTogetherThenCanBeRetried() {
        var claim = telemetry.claim(TARGET, NOW, Duration.ofSeconds(45)).orElseThrow();
        var response = feed.fetch(TARGET);
        jdbc.execute("ALTER TABLE ingestion.telemetry_audit ADD CONSTRAINT test_fail_completion CHECK (action <> 'POLL_COMPLETED') NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> telemetry.complete(claim, response, NOW, 120));
            assertEquals(0, count("ingestion.raw_observation"));
            assertEquals(0, count("ingestion.feed_status"));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT outcome FROM ingestion.poll_run", String.class));
            assertEquals(1, count("ingestion.telemetry_audit"));
        } finally {
            jdbc.execute("ALTER TABLE ingestion.telemetry_audit DROP CONSTRAINT test_fail_completion");
        }
        assertTrue(telemetry.complete(claim, response, NOW, 120));
        assertFalse(telemetry.complete(claim, response, NOW, 120));
        assertEquals(1, count("ingestion.raw_observation"));
        assertEquals(2, count("ingestion.telemetry_audit"));
    }

    @Test
    void expiredLeaseRecordsFailureAndFencesLateCompletion() {
        var old = telemetry.claim(TARGET, NOW, Duration.ofSeconds(45)).orElseThrow();
        assertTrue(telemetry.claim(TARGET, NOW.plusSeconds(44), Duration.ofSeconds(45)).isEmpty());
        assertTrue(telemetry.claim(TARGET, NOW.plusSeconds(45), Duration.ofSeconds(45)).isEmpty());
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT error_code FROM ingestion.poll_run", String.class));
        assertFalse(telemetry.complete(old, feed.fetch(TARGET), NOW.plusSeconds(46), 120));
        clock.now = NOW.plusSeconds(60);
        assertTrue(poll().committed());
        assertEquals(1, count("ingestion.raw_observation"));
        assertEquals(4, count("ingestion.telemetry_audit"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoveryFinalizesExpiredPollsForDisabledOrDeletedTargets(boolean deleted) {
        jdbc.update("""
                INSERT INTO ingestion.poll_target (source,scope_key,stop_code,enabled,poll_interval_seconds,created_at,updated_at)
                VALUES ('DATAMALL','stop:44009','44009',true,60,?,?) ON CONFLICT DO NOTHING
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        var claim = telemetry.claim(TARGET, NOW, Duration.ofSeconds(45)).orElseThrow();
        if (deleted) jdbc.update("DELETE FROM ingestion.poll_target WHERE scope_key='stop:44009'");
        else jdbc.update("UPDATE ingestion.poll_target SET enabled=false WHERE scope_key='stop:44009'");
        assertTrue(telemetry.targets().isEmpty());
        assertTrue(telemetry.expiredIds(NOW.plusSeconds(44), 100).isEmpty());
        assertEquals(java.util.List.of(claim.id()), telemetry.expiredIds(NOW.plusSeconds(45), 100));
        clock.now = NOW.plusSeconds(45);
        new com.csd.occ.ingestion.infrastructure.scheduling.PollRecoverySchedule(telemetry, clock).recover();
        assertFalse(telemetry.recoverExpired(claim.id(), NOW.plusSeconds(46)));
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT error_code FROM ingestion.poll_run WHERE id=?", String.class, claim.id()));
        assertEquals(2, count("ingestion.telemetry_audit"));
        assertFalse(telemetry.complete(claim, feed.fetch(TARGET), NOW.plusSeconds(46), 120));
        jdbc.update("DELETE FROM ingestion.poll_target WHERE scope_key='stop:44009'");
    }

    @Test
    void concurrentRecoveryWritesOneCompletionAndAuditFailureRemainsRetryable() throws Exception {
        var claim = telemetry.claim(TARGET, NOW, Duration.ofSeconds(45)).orElseThrow();
        jdbc.execute("ALTER TABLE ingestion.telemetry_audit ADD CONSTRAINT test_recovery_failure CHECK(action <> 'POLL_COMPLETED') NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> telemetry.recoverExpired(claim.id(), NOW.plusSeconds(45)));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT outcome FROM ingestion.poll_run", String.class));
        } finally { jdbc.execute("ALTER TABLE ingestion.telemetry_audit DROP CONSTRAINT test_recovery_failure"); }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> telemetry.recoverExpired(claim.id(), NOW.plusSeconds(45)));
            var second = executor.submit(() -> telemetry.recoverExpired(claim.id(), NOW.plusSeconds(45)));
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertEquals(2, count("ingestion.telemetry_audit"));
    }

    @Test
    void recoveringLegacyPollWithoutLeaseCannotOverwriteNewerHealthyFeed() {
        poll();
        UUID old = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ingestion.poll_run (id,source,scope_key,idempotency_key,started_at,outcome)
                VALUES (?, 'DATAMALL','stop:44009',?,?,'RUNNING')
                """, old, old.toString(), Timestamp.from(NOW.minusSeconds(120)));
        assertTrue(telemetry.expiredIds(NOW, 100).contains(old));
        assertTrue(telemetry.recoverExpired(old, NOW));
        assertEquals("HEALTHY", jdbc.queryForObject("SELECT status FROM ingestion.feed_status", String.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT last_success_at FROM ingestion.feed_status", Timestamp.class).toInstant());
        assertEquals(0, jdbc.queryForObject("SELECT consecutive_failures FROM ingestion.feed_status", Integer.class));
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT reason FROM ingestion.telemetry_audit WHERE poll_run_id=?", String.class, old));
    }

    @Test
    void concurrentPollersMakeOneHttpRequestAndOneAuditPair() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> polling.poll(TARGET, Duration.ofSeconds(45), 120));
            var second = executor.submit(() -> polling.poll(TARGET, Duration.ofSeconds(45), 120));
            int claimed = (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0);
            assertEquals(1, claimed);
        }
        assertEquals(1, REQUESTS.get());
        assertEquals(1, count("ingestion.raw_observation"));
        assertEquals(2, count("ingestion.telemetry_audit"));
    }

    private ArrivalPollingService.Completion poll() {
        return polling.poll(TARGET, Duration.ofSeconds(45), 120).orElseThrow();
    }

    private BunchingConsumptionService.BatchResult consume() {
        return consumer.consume(100, Duration.ofSeconds(90), Duration.ofSeconds(120));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void seedTopology() {
        UUID reference = UUID.randomUUID();
        jdbc.update("INSERT INTO ingestion.reference_dataset_version (id,dataset,version_key,checksum,status,imported_at) VALUES (?, 'BUS_ROUTES','fixture',?,'ACTIVE',?)",
                reference, "a".repeat(64), Timestamp.from(NOW));
        jdbc.update("INSERT INTO ingestion.bus_service VALUES (?, '190', 'SMRT', 'TRUNK', ?)", reference, Timestamp.from(NOW));
        for (String stop : new String[]{"44009", "10009"}) {
            jdbc.update("INSERT INTO ingestion.bus_stop VALUES (?, ?, 'Synthetic road', 'Synthetic stop', ST_SetSRID(ST_Point(103.8, 1.3), 4326), ?)", reference, stop, Timestamp.from(NOW));
        }
        jdbc.update("INSERT INTO ingestion.bus_route_stop VALUES (?, '190', 'SMRT', 1, 1, '44009', 0, ?)", reference, Timestamp.from(NOW));
        jdbc.update("INSERT INTO ingestion.bus_route_stop VALUES (?, '190', 'SMRT', 1, 2, '10009', 1, ?)", reference, Timestamp.from(NOW));
    }

    private static String payload() {
        return """
                {"BusStopCode":"44009","Services":[{"ServiceNo":"190","Operator":"SMRT",
                "NextBus":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"1","EstimatedArrival":"2026-09-22T04:05:00Z"},
                "NextBus2":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"1","EstimatedArrival":"2026-09-22T04:06:00Z"},
                "NextBus3":{"EstimatedArrival":""}}]}
                """;
    }

    private static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v3/BusArrival", exchange -> {
                REQUESTS.incrementAndGet();
                ACCOUNT_KEY.set(exchange.getRequestHeaders().getFirst("AccountKey"));
                var response = RESPONSE.get();
                try (exchange) {
                    if (response.delayMillis() > 0) {
                        try { Thread.sleep(response.delayMillis()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private record StubResponse(int status, String body, int delayMillis) {}

    @Configuration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ArrivalPollingProperties.class)
    @EntityScan(basePackageClasses = BunchingObservation.class)
    @EnableJpaRepositories(basePackageClasses = BunchingObservationRepository.class)
    @Import({ArrivalPollingService.class, DataMallArrivalFeedAdapter.class, PostgresArrivalTelemetryAdapter.class,
            BunchingConsumptionService.class, BunchingRecordingService.class, PostgresStoredArrivalAdapter.class,
            StoredArrivalMapper.class, JacksonBunchingEvidenceAdapter.class,
            com.csd.occ.bunching.infrastructure.postgres.PostgresBunchingAlertAdapter.class})
    static class Application {
        @Bean ControlledClock clock() { return new ControlledClock(); }
        @Bean BunchingPolicy policy() { return new BunchingPolicy(); }
    }

    static class ControlledClock extends Clock {
        private volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
