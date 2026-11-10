package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.csd.occ.ingestion.domain.TrafficIncidentFeedPort;
import com.csd.occ.ingestion.domain.TrafficIncidentPollingService;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.PacingMetric;
import com.csd.occ.ingestion.domain.PacingService;
import com.csd.occ.ingestion.domain.PacingState;
import com.csd.occ.ingestion.domain.PollRunRepository;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.domain.RawObservationRepository;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.config.DemoReplaySchedule;
import com.csd.occ.ingestion.infrastructure.config.PacingSchedule;
import com.csd.occ.ingestion.infrastructure.config.TrafficIncidentSchedule;
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
    @MockitoBean TrafficIncidentSchedule trafficIncidentSchedule;
    @MockitoBean DemoReplaySchedule demoReplaySchedule;
    @MockitoBean PacingSchedule pacingSchedule;
    @MockitoBean BunchingControlPort bunching;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PollRunRepository runs;
    @Autowired RawObservationRepository observations;
    @Autowired BusArrivalObservationRepository arrivals;

    @Test
    void keepsReadinessAvailableAndDeniesRemovedMetricsEndpoint() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(get("/actuator/prometheus").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Autowired BusArrivalService polling;
    @Autowired BunchingFeedService bunchingFeed;
    @Autowired DemoRunService demoRuns;
    @Autowired DemoReplayService replay;
    @Autowired DemoModeService demoMode;
    @Autowired ReferenceTopologyPort topology;
    @Autowired com.csd.occ.ingestion.domain.RetentionService retention;
    @Autowired TrafficIncidentPollFeedService trafficIncidentPollFeed;
    @Autowired TrafficIncidentPollingService trafficIncidentPolling;
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
    }

    @Test
    void currentArrivalsFiltersAndSortsUsingVersionedStopNamesBeforePaging() throws Exception {
        var alpha = insertCurrentArrival("ARR001", "Alpha Point", Instant.parse("2026-10-25T23:50:00Z"));
        var alphaTie = insertCurrentArrival("ARR007", "Alpha Point", Instant.parse("2026-10-25T23:50:00Z"));
        var bravo = insertCurrentArrival("ARR002", "Bravo", Instant.parse("2026-10-25T23:55:00Z"));
        var target = insertCurrentArrival("ARR003", "Target Terminus", Instant.parse("2026-10-26T00:10:00Z"));
        var wildcard = insertCurrentArrival("ARR004", "Target % Stop", Instant.parse("2026-10-26T00:05:00Z"));
        var underscore = insertCurrentArrival("ARR005", "Under_score Stop", Instant.parse("2026-10-26T00:08:00Z"));
        var noArrival = insertCurrentArrival("ARR006", "No Arrival", null);
        insertVersionedStop(UUID.randomUUID(), "ARR004", "Different version of the same stop");

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("stopName", "  tArGeT  ")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].observationId").value(wildcard.toString()))
                .andExpect(jsonPath("$.items[0].stopName").value("Target % Stop"));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("stopName", "Target")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].observationId").value(target.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("stopName", "%").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].observationId").value(wildcard.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("stopName", "_").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].observationId").value(underscore.toString()));

        UUID firstAlphaId = alpha.toString().compareTo(alphaTie.toString()) <= 0 ? alpha : alphaTie;
        UUID secondAlphaId = firstAlphaId.equals(alpha) ? alphaTie : alpha;
        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("sortBy", "stopName")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(firstAlphaId.toString()));
        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("sortBy", "stopName")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(secondAlphaId.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("stopName", " ")
                        .param("sortBy", "stopName").param("sortDirection", "asc")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(7))
                .andExpect(jsonPath("$.items[0].observationId").value(firstAlphaId.toString()))
                .andExpect(jsonPath("$.items[1].observationId").value(secondAlphaId.toString()))
                .andExpect(jsonPath("$.items[2].observationId").value(bravo.toString()))
                .andExpect(jsonPath("$.items[3].observationId").value(noArrival.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("sortBy", "stopName")
                        .param("sortDirection", "desc").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(underscore.toString()))
                .andExpect(jsonPath("$.items[1].observationId").value(target.toString()))
                .andExpect(jsonPath("$.items[5].observationId").value(firstAlphaId.toString()))
                .andExpect(jsonPath("$.items[6].observationId").value(secondAlphaId.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("sortBy", "arrival")
                        .param("sortDirection", "desc").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(target.toString()))
                .andExpect(jsonPath("$.items[6].observationId").value(noArrival.toString()));

        mvc.perform(get("/api/v1/arrivals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .param("serviceId", "ARRIVAL-TEST").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(firstAlphaId.toString()))
                .andExpect(jsonPath("$.items[6].observationId").value(noArrival.toString()));
    }

    @Test
    void trafficIncidentPollIsStoredAndExposedAsAnObservationOnlyCompletedFeed() {
        String payload = "[{\"Type\":\"Road Works\",\"Latitude\":1.3000000,"
                + "\"Longitude\":103.8000000,\"Message\":\"Road works near test junction\"}]";
        when(dataMall.trafficIncidents()).thenReturn(new TrafficIncidentFeedPort.TrafficIncidentBatch(200,
                payload, List.of(new TrafficIncidentFeedPort.TrafficIncident("a".repeat(64), "Road Works",
                        new java.math.BigDecimal("1.3000000"), new java.math.BigDecimal("103.8000000"),
                        "Road works near test junction", null)), List.of()));

        trafficIncidentPolling.pollOnce();

        UUID pollId = jdbc.queryForObject("SELECT id FROM ingestion.poll_run "
                + "WHERE source='TRAFFIC_DATAMALL' AND outcome='SUCCESS' "
                + "ORDER BY completed_at DESC, id DESC LIMIT 1", UUID.class);
        var poll = trafficIncidentPollFeed.page(null, null, 100).polls().stream()
                .filter(value -> value.pollRunId().equals(pollId)).findFirst().orElseThrow();
        assertThat(poll.complete()).isTrue();
        assertThat(poll.classification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(poll.incidents()).singleElement().satisfies(incident -> {
            assertThat(incident.sourceIncidentKey()).isEqualTo("a".repeat(64));
            assertThat(incident.classification()).isEqualTo("OBSERVATION_ONLY");
            assertThat(incident.demoRunId()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT payload FROM ingestion.raw_observation "
                + "WHERE poll_run_id=?", String.class, pollId))
                .contains("Road Works", "Road works near test junction");
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
        assertThat(arrivals.findCurrent("44251", "190", null, "arrival", "asc", PageRequest.of(0, 50))
                .getContent()).isEmpty();
        assertThat(arrivals.findHistory("44251", "190", null, null, PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion.traffic_incident_observation "
                + "WHERE demo_run_id=? AND classification='SIMULATION'", Integer.class, started.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion.hazard_observation "
                + "WHERE demo_run_id=?", Integer.class, started.id())).isEqualTo(9);
        var trafficPolls = trafficIncidentPollFeed.page(null, null, 250).polls().stream()
                .filter(poll -> started.id().equals(poll.demoRunId())).toList();
        assertThat(trafficPolls).hasSize(DemoReplayScenario.STEP_COUNT).allMatch(poll -> poll.complete()
                && "SIMULATION".equals(poll.classification()));
        assertThat(trafficPolls.stream().flatMap(poll -> poll.incidents().stream())).isEmpty();

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
        assertThat(jdbc.queryForObject("select count(*) from ingestion.hazard_observation where demo_run_id = ?", Integer.class, started.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_vehicle_assignment where demo_run_id = ?", Integer.class, started.id())).isZero();
    }

    @Autowired PacingService pacing;

    @Test
    void pacingUsesSimulationRegistrationsFromTheLatestReplayStepOnlyWhileTheRunIsActive() {
        topology.replaceIfChanged(new BusReferencePort.BusReferenceData(
                List.of(new BusReferencePort.BusStopRecord("44251", "Demo Road", "Demo Stop",
                        new java.math.BigDecimal("1.377"), new java.math.BigDecimal("103.771"))),
                List.of(new BusReferencePort.BusServiceRecord("190", "SBST", "TRUNK"),
                        new BusReferencePort.BusServiceRecord("972", "SBST", "TRUNK")),
                List.of(new BusReferencePort.BusRouteStopRecord("190", "SBST", 1, 1, "44251",
                        java.math.BigDecimal.ZERO),
                        new BusReferencePort.BusRouteStopRecord("972", "SBST", 1, 1, "44251",
                                java.math.BigDecimal.ZERO))), "e".repeat(64));
        when(bunching.alerts(any(UUID.class))).thenReturn(List.of());
        DemoRunDetails started = demoRuns.start();
        for (int step = 0; step < DemoReplayScenario.STEP_COUNT; step++) replay.replayNext();

        List<PacingMetric> simulation = pacing.refresh().metrics().stream()
                .filter(metric -> "SIMULATION".equals(metric.classification())).toList();

        // Only the latest step contributes: three route-190 buses from the injected simulation fleet.
        assertThat(simulation).extracting(PacingMetric::vehicleReference).containsExactlyInAnyOrder(
                "DEMO-190-01", "DEMO-190-02", "DEMO-190-03");
        for (String serviceId : List.of("190")) {
            List<PacingMetric> route = simulation.stream()
                    .filter(metric -> serviceId.equals(metric.serviceId())).toList();
            assertThat(route).extracting(PacingMetric::vehicleReference).containsExactlyElementsOf(
                    List.of("DEMO-190-01", "DEMO-190-02", "DEMO-190-03"));
            for (int index = 0; index < route.size(); index++) {
                PacingMetric metric = route.get(index);
                assertThat(metric.stopCode()).isEqualTo("44251");
                assertThat(metric.directionId()).isEqualTo(1);
                if (index == 0) assertThat(metric.ahead()).isEmpty();
                else assertThat(metric.ahead()).hasValueSatisfying(gap -> {
                    assertThat(gap.headway()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(gap.state()).isEqualTo(PacingState.TOO_CLOSE);
                });
                if (index == route.size() - 1) assertThat(metric.behind()).isEmpty();
                else assertThat(metric.behind()).hasValueSatisfying(gap -> {
                    assertThat(gap.headway()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(gap.state()).isEqualTo(PacingState.TOO_CLOSE);
                });
            }
        }

        demoRuns.close(started.id());

        assertThat(pacing.refresh().metrics()).noneMatch(metric -> "SIMULATION".equals(metric.classification()));
    }

    @Test
    void servesTheStaticContractAndSwaggerUi() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var ui = mvc.perform(get("/swagger-ui/index.html").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ui).doesNotContain("${").doesNotContain("@project.artifactId@")
                .contains("/v3/ingestion.json");
        var bundle = java.util.regex.Pattern.compile("src=\"(/webjars/swagger-ui/[^\"]+/swagger-ui-bundle\\.js)\"")
                .matcher(ui);
        assertThat(bundle.find()).as("Swagger UI bundle path").isTrue();
        mvc.perform(get(bundle.group(1)).with(admin)).andExpect(status().isOk());
        mvc.perform(get("/v3/ingestion.json").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OCC Ingestion API"));
        mvc.perform(get("/swagger-ui.html").with(admin))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/swagger-ui/index.html"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "occ.exportOpenapi", matches = "true")
    void exportsGeneratedIngestionContract() throws Exception {
        // The absolute URL reproduces the committed server URL that the runtime contract check expects.
        String contract = mvc.perform(get("http://localhost:6767/v3/api-docs").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.nio.file.Path output = java.nio.file.Path.of("openapi", "ingestion.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, contract);
        var packaged = getClass().getResourceAsStream("/static/v3/ingestion.json");
        assertThat(packaged).as("packaged OpenAPI contract").isNotNull();
        assertThat(new String(packaged.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(contract);
        mvc.perform(get("/v3/ingestion.json").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string(contract));
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
                context.getBean(com.csd.occ.ingestion.domain.PollTargetRepository.class),
                context.getBean(com.csd.occ.ingestion.domain.HazardObservationRepository.class));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        Long firstPurgeCount = transaction.execute(status -> pruning.purgeExpiredObservations());
        assertThat(firstPurgeCount).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.reference_dataset_version where id = ?", Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.reference_dataset_version where id = ?", Integer.class, boundary)).isEqualTo(1);
        Long secondPurgeCount = transaction.execute(status -> pruning.purgeExpiredObservations());
        assertThat(secondPurgeCount).isZero();
    }

    private UUID insertCurrentArrival(String stopCode, String description, Instant estimatedArrivalAt) {
        UUID versionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID pollRunId = UUID.randomUUID();
        UUID rawObservationId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        var fetchedAt = Instant.parse("2026-10-25T00:00:00Z");

        insertVersionedStop(versionId, stopCode, description);
        jdbc.update("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome)
                VALUES (?, 'DATAMALL', ?, ?, ?, ?, 'SUCCESS')
                """, pollRunId, "arrival-search:" + suffix, suffix,
                java.sql.Timestamp.from(fetchedAt), java.sql.Timestamp.from(fetchedAt));
        jdbc.update("""
                INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status)
                VALUES (?, ?, 'DATAMALL', ?, '{}'::jsonb, '{}'::jsonb, ?, ?, ?, 200)
                """, rawObservationId, pollRunId, suffix, "b".repeat(64),
                java.sql.Timestamp.from(fetchedAt), java.sql.Timestamp.from(fetchedAt));
        jdbc.update("""
                INSERT INTO ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, service_id, stop_code,
                     operator, direction_id, visit_order, estimated_arrival_at, classification,
                     fetched_at, ingested_at, record_checksum)
                VALUES (?, ?, ?, ?, 'ARRIVAL-TEST', ?, 'SMRT', 1, 1, ?, 'OBSERVATION_ONLY', ?, ?, ?)
                """, observationId, rawObservationId, pollRunId, versionId, stopCode,
                estimatedArrivalAt == null ? null : java.sql.Timestamp.from(estimatedArrivalAt),
                java.sql.Timestamp.from(fetchedAt), java.sql.Timestamp.from(fetchedAt), "c".repeat(64));
        return observationId;
    }

    private void insertVersionedStop(UUID versionId, String stopCode, String description) {
        var at = Instant.now();
        jdbc.update("""
                INSERT INTO ingestion.reference_dataset_version
                    (id, dataset, version_key, checksum, status, imported_at, activated_at)
                VALUES (?, 'BUS_TOPOLOGY', ?, ?, 'SUPERSEDED', ?, ?)
                """, versionId, "arrival-search-" + UUID.randomUUID(), "a".repeat(64),
                java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
        jdbc.update("""
                INSERT INTO ingestion.bus_stop
                    (reference_version_id, stop_code, road_name, description, location)
                VALUES (?, ?, 'Fixture Road', ?, ST_SetSRID(ST_MakePoint(103.8, 1.3), 4326))
                """, versionId, stopCode, description);
    }
}
