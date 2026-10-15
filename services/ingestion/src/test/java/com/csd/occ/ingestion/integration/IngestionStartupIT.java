package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.csd.occ.ingestion.domain.BusArrivalObservationRepository;
import com.csd.occ.ingestion.domain.BusArrivalSnapshot;
import com.csd.occ.ingestion.domain.BusReferencePort;
import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.BunchingFeedService;
import com.csd.occ.ingestion.domain.DemoModeService;
import com.csd.occ.ingestion.domain.DemoReplayScenario;
import com.csd.occ.ingestion.domain.DemoReplayService;
import com.csd.occ.ingestion.domain.DemoRunDetails;
import com.csd.occ.ingestion.domain.TrafficIncidentPollFeedService;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.PollRunRepository;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.domain.RawObservationRepository;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.config.DemoReplaySchedule;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.profiles.active=test", "occ.datamall.account-key=synthetic-key",
                "occ.ingestion.import-reference-on-startup=false", "occ.ingestion.seed-targets-on-startup=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class IngestionStartupIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/ingestion-test-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_ingestion");
        registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }

    @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule;
    @MockitoBean DemoReplaySchedule demoReplaySchedule;
    @MockitoBean BunchingControlPort bunching;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired PollRunRepository runs;
    @Autowired RawObservationRepository observations;
    @Autowired BusArrivalObservationRepository arrivals;

    @Test
    void exposesFeedMetricsOnlyToAdministrators() throws Exception {
        jdbc.update("""
                INSERT INTO ingestion.feed_status
                    (source, scope_key, status, last_attempt_at, consecutive_failures, stale_after_seconds, updated_at)
                VALUES ('METRICS_TEST', 'scope', 'FAILED', CURRENT_TIMESTAMP, 1, 60, CURRENT_TIMESTAMP)
                ON CONFLICT (source, scope_key) DO UPDATE SET status = 'FAILED', last_success_at = NULL
                """);

        assertThat(meterRegistry.get("occ.ingestion.feed.unhealthy_count").gauge().value())
                .isGreaterThanOrEqualTo(1.0);
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(jwt()
                        .jwt(token -> token.claim("occ_role", "ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("occ_ingestion_feed_unhealthy_count"),
                        org.hamcrest.Matchers.containsString("occ_ingestion_retention_deleted_rows_total"))));
    }
    @Autowired BusArrivalService polling;
    @Autowired BunchingFeedService bunchingFeed;
    @Autowired DemoRunService demoRuns;
    @Autowired DemoReplayService replay;
    @Autowired DemoModeService demoMode;
    @Autowired ReferenceTopologyPort topology;
    @Autowired com.csd.occ.ingestion.domain.RetentionService retention;
    @Autowired TrafficIncidentPollFeedService trafficIncidentPollFeed;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void migrationsAndPollingWorkWithServiceCredentials() {
        assertThat(jdbc.queryForObject("select count(*) from ingestion.flyway_schema_history where success", Integer.class))
                .isGreaterThanOrEqualTo(4);
        UUID existingRun = UUID.randomUUID();
        jdbc.update("insert into ingestion.poll_run (id, source, scope_key, idempotency_key, started_at, outcome) "
                + "values (?, 'TEST', 'fixture', 'fixture', '2026-09-24T00:00:00Z', 'SUCCESS')", existingRun);
        assertThat(runs.findById(existingRun).orElseThrow().getStartedAt())
                .isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
        ReferenceTopologyPort.ReferenceTopologyVersion referenceVersion = topology.replaceIfChanged(
                new BusReferencePort.BusReferenceData(
                        java.util.List.of(new BusReferencePort.BusStopRecord("44009", "Fixture Road", "Fixture Stop",
                                new java.math.BigDecimal("1.3"), new java.math.BigDecimal("103.8"))),
                        java.util.List.of(new BusReferencePort.BusServiceRecord("190", "SBST", "TRUNK")),
                        java.util.List.of(new BusReferencePort.BusRouteStopRecord("190", "SBST", 1, 1, "44009",
                                java.math.BigDecimal.ZERO))), "a".repeat(64));
        assertThat(referenceVersion.id()).isNotNull();
        jdbc.update("insert into ingestion.poll_target (source, scope_key, stop_code, service_id, enabled, created_at, updated_at) "
                + "values ('DATAMALL', 'service:190:stop:44009', '44009', '190', true, now(), now())");
        when(dataMall.arrivalsAt("44009", "190"))
                .thenReturn(new BusArrivalSnapshot(200, "{}", java.util.List.of(), java.util.List.of()));
        polling.poll();
        assertThat(observations.findAll()).singleElement().satisfies(observation -> {
            assertThat(observation.getPayload()).isEqualTo("{}");
            assertThat(observation.getPayloadChecksum()).hasSize(64);
            assertThat(observation.getFetchedAt()).isNotNull();
        });
        assertThat(bunchingFeed.page(0, 100).items()).hasSize(1);
        assertThat(meterRegistry.get("occ.ingestion.arrival.feed_sequence").gauge().value())
                .isGreaterThan(0.0);
    }

    @Test
    void demoPageIsPublicButDemoApiRequiresAnAuthorizedUser() throws Exception {
        mvc.perform(get("/demo.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/api/v1/demo-mode"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void referenceTopologyQueriesReturnRoutesAndStopsFromTheActiveVersion() {
        ReferenceTopologyPort.ReferenceTopologyVersion version = topology.replaceIfChanged(
                new BusReferencePort.BusReferenceData(
                        java.util.List.of(
                                new BusReferencePort.BusStopRecord("44009", "Fixture Road", "First stop",
                                        new java.math.BigDecimal("1.3"), new java.math.BigDecimal("103.8")),
                                new BusReferencePort.BusStopRecord("44019", "Fixture Road", "Second stop",
                                        new java.math.BigDecimal("1.31"), new java.math.BigDecimal("103.81"))),
                        java.util.List.of(new BusReferencePort.BusServiceRecord("190", "SBST", "TRUNK")),
                        java.util.List.of(
                                new BusReferencePort.BusRouteStopRecord("190", "SBST", 1, 1, "44009",
                                        java.math.BigDecimal.ZERO),
                                new BusReferencePort.BusRouteStopRecord("190", "SBST", 1, 2, "44019",
                                        new java.math.BigDecimal("0.5")),
                                new BusReferencePort.BusRouteStopRecord("190", "SBST", 2, 1, "44019",
                                        java.math.BigDecimal.ZERO),
                                new BusReferencePort.BusRouteStopRecord("190", "SBST", 2, 2, "44009",
                                        new java.math.BigDecimal("0.5")))), "b".repeat(64));

        assertThat(topology.busRoutes(version.id(), PageRequest.of(0, 50)).getContent())
                .extracting(ReferenceTopologyPort.BusRouteSummary::serviceId).containsExactly("190");
        assertThat(topology.busRoute(version.id(), "190")).hasValueSatisfying(route -> {
            assertThat(route.operators()).singleElement().satisfies(operator -> {
                assertThat(operator.directions()).extracting(ReferenceTopologyPort.BusRouteDirection::directionId)
                        .containsExactly(1, 2);
                assertThat(operator.directions().getFirst().stops()).extracting(ReferenceTopologyPort.BusRouteStop::stopCode)
                        .containsExactly("44009", "44019");
            });
        });
        assertThat(topology.busStops(version.id(), PageRequest.of(0, 50)).getContent())
                .extracting(ReferenceTopologyPort.BusStop::stopCode).containsExactly("44009", "44019");
        assertThat(topology.busStop(version.id(), "44009")).hasValueSatisfying(stop ->
                assertThat(stop.description()).isEqualTo("First stop"));
    }

    @Test
    void demoReplayRowsRemainRunScopedAndUnavailableFromLiveQueries() {
        topology.replaceIfChanged(new BusReferencePort.BusReferenceData(
                List.of(new BusReferencePort.BusStopRecord("44251", "Demo Road", "Demo Stop",
                        new java.math.BigDecimal("1.377"), new java.math.BigDecimal("103.771"))),
                List.of(new BusReferencePort.BusServiceRecord("190", "SBST", "TRUNK")),
                List.of(new BusReferencePort.BusRouteStopRecord("190", "SBST", 1, 1, "44251",
                        java.math.BigDecimal.ZERO))), "d".repeat(64));
        when(bunching.alerts(any(UUID.class))).thenReturn(List.of());

        DemoRunDetails started = demoRuns.start();
        for (int step = 0; step < DemoReplayScenario.STEP_COUNT; step++) replay.replayNext();
        DemoRunDetails completed = demoRuns.status(started.id());
        var simulationPage = bunchingFeed.demoPage(started.id(), 0, 100);
        var simulationIds = simulationPage.items().stream()
                .map(BunchingFeedService.Snapshot::rawObservationId).collect(java.util.stream.Collectors.toSet());

        assertThat(demoMode.enabled()).isTrue();
        assertThat(completed.status()).isEqualTo("REPLAY_COMPLETE");
        assertThat(simulationPage.items()).hasSize(DemoReplayScenario.STEP_COUNT);
        assertThat(bunchingFeed.page(0, 100).items())
                .noneMatch(snapshot -> simulationIds.contains(snapshot.rawObservationId()));
        assertThat(arrivals.findCurrent("44251", "190", PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(arrivals.findHistory("44251", "190", null, null, PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion.traffic_incident_observation "
                + "WHERE demo_run_id=? AND classification='SIMULATION'", Integer.class, started.id())).isEqualTo(2);
        var trafficPolls = trafficIncidentPollFeed.page(null, null, 250).polls().stream()
                .filter(poll -> started.id().equals(poll.demoRunId())).toList();
        assertThat(trafficPolls).hasSize(DemoReplayScenario.STEP_COUNT).allMatch(poll -> poll.complete()
                && "SIMULATION".equals(poll.classification()));
        assertThat(trafficPolls.stream().flatMap(poll -> poll.incidents().stream())).hasSize(2);

        DemoRunDetails closed = demoRuns.close(started.id());

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(demoMode.enabled()).isFalse();
        assertThat(demoRuns.active()).isEmpty();
        org.mockito.Mockito.verify(bunching).selectDemo(started.id());
        org.mockito.Mockito.verify(bunching).selectLive();
        jdbc.update("update ingestion.demo_run set closed_at = now() - interval '2 days' where id = ?", started.id());
        when(bunching.protectedDemoRuns()).thenReturn(List.of(started.id()));
        retention.purgeExpiredObservations();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.raw_observation where demo_run_id = ?", Integer.class, started.id())).isGreaterThan(0);
        when(bunching.protectedDemoRuns()).thenReturn(List.of());
        assertThat(retention.purgeExpiredObservations()).isGreaterThan(0);
        retention.purgeExpiredObservations();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_run where id = ?", Integer.class, started.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.traffic_incident_observation where demo_run_id = ?", Integer.class, started.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_vehicle_assignment where demo_run_id = ?", Integer.class, started.id())).isZero();
    }

    @Test void topologyPruningPreservesOldActiveAndBoundaryRetiredVersions() {
        var fixed = java.time.Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), java.time.ZoneOffset.UTC);
        var cutoff = fixed.instant().minus(java.time.Duration.ofHours(12));
        UUID expired = UUID.randomUUID();
        UUID boundary = UUID.randomUUID();
        for (UUID id : List.of(expired, boundary)) {
            jdbc.update("insert into ingestion.reference_dataset_version (id,dataset,version_key,checksum,status,imported_at,retired_at) values (?, 'RETENTION_TEST', ?, ?, 'SUPERSEDED', '2020-01-01', ?)",
                    id, id.toString(), "f".repeat(64), java.sql.Timestamp.from(id.equals(expired) ? cutoff.minusNanos(1000) : cutoff));
        }
        when(bunching.protectedDemoRuns()).thenReturn(List.of());
        var pruning = new com.csd.occ.ingestion.domain.RetentionService(arrivals, observations, runs,
                context.getBean(com.csd.occ.ingestion.domain.ReferenceDatasetVersionRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.BusRouteStopRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.BusServiceRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.BusStopRepository.class),
                new com.csd.occ.ingestion.infrastructure.config.IngestionProperties(List.of("190"), 180, java.time.Duration.ofHours(12)), fixed,
                context.getBean(com.csd.occ.ingestion.domain.DemoRunRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.TrafficIncidentObservationRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.DemoVehicleAssignmentRepository.class), bunching,
                context.getBean(com.csd.occ.ingestion.domain.PollTargetRepository.class));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        Long firstPurgeCount = transaction.execute(status -> pruning.purgeExpiredObservations());
        assertThat(firstPurgeCount).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.reference_dataset_version where id = ?", Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.reference_dataset_version where id = ?", Integer.class, boundary)).isEqualTo(1);
        Long secondPurgeCount = transaction.execute(status -> pruning.purgeExpiredObservations());
        assertThat(secondPurgeCount).isZero();
    }
}
