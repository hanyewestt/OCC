package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.domain.BusArrivalSnapshot;
import com.csd.occ.ingestion.domain.BusReferencePort;
import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.domain.BunchingFeedService;
import com.csd.occ.ingestion.domain.PollRunRepository;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.domain.RawObservationRepository;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=test", "occ.datamall.account-key=synthetic-key",
                "occ.ingestion.import-reference-on-startup=false", "occ.ingestion.seed-targets-on-startup=false"})
@Testcontainers(disabledWithoutDocker = true)
class IngestionStartupIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withEnv("OCC_INGESTION_DB_PASSWORD", "synthetic-ingestion")
            .withEnv("OCC_OPERATIONS_DB_PASSWORD", "synthetic-operations")
            .withEnv("OCC_PLANNING_DB_PASSWORD", "synthetic-planning")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "../../infra/local/postgres/init/01_schemas_and_roles.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schemas_and_roles.sh");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_ingestion");
        registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }

    @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule;
    @Autowired JdbcTemplate jdbc;
    @Autowired PollRunRepository runs;
    @Autowired RawObservationRepository observations;
    @Autowired BusArrivalService polling;
    @Autowired BunchingFeedService bunchingFeed;
    @Autowired ReferenceTopologyPort topology;

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
        assertThat(jdbc.queryForObject("select count(*) from ingestion.bunching_snapshot", Integer.class))
                .isEqualTo(1);
        assertThat(bunchingFeed.page(0, 100).items()).hasSize(1);
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
}
