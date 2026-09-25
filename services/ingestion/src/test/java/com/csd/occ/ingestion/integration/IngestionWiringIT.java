package com.csd.occ.ingestion.integration;

import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.domain.BusArrivalObservationRepository;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import com.csd.occ.ingestion.IngestionApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IngestionApplication.class, properties = {
        "spring.main.web-application-type=none", "spring.datasource.password=synthetic-ingestion",
        "occ.datamall.account-key=synthetic-only", "occ.ingestion.import-reference-on-startup=false",
        "occ.ingestion.seed-targets-on-startup=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IngestionWiringIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withEnv("OCC_INGESTION_DB_PASSWORD", "synthetic-ingestion")
            .withEnv("OCC_OPERATIONS_DB_PASSWORD", "synthetic-operations")
            .withEnv("OCC_PLANNING_DB_PASSWORD", "synthetic-planning")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                    "../../infra/local/postgres/init/01_schemas_and_roles.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schemas_and_roles.sh");

    @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule;
    private final BusArrivalService polling;
    private final BusArrivalObservationRepository observations;
    private final JdbcTemplate jdbc;

    @Autowired
    IngestionWiringIT(BusArrivalService polling, BusArrivalObservationRepository observations, JdbcTemplate jdbc) {
        this.polling = polling;
        this.observations = observations;
        this.jdbc = jdbc;
    }

    @Test
    void productionApplicationUsesTheExistingPollingServiceAndAppliesTheBunchingContract() {
        assertNotNull(polling);
        assertTrue(jdbc.queryForObject("SELECT EXISTS (SELECT FROM pg_views WHERE schemaname = 'ingestion' "
                + "AND viewname = 'bunching_snapshot')", Boolean.class));
    }

    @Test
    void mapQueryKeepsOnlyMonitoredPositionsWithUsableCoordinates() {
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        Timestamp fetchedAt = Timestamp.from(now.minusSeconds(30));
        jdbc.update("""
                insert into ingestion.reference_dataset_version
                    (id, dataset, version_key, checksum, status, imported_at)
                values (?, 'BUS', ?, repeat('a', 64), 'CANDIDATE', ?)
                """, versionId, versionId.toString(), fetchedAt);
        jdbc.update("""
                insert into ingestion.bus_stop
                    (reference_version_id, stop_code, road_name, description, location, imported_at)
                values (?, '44251', 'Demo Road', 'Demo Stop',
                    ST_SetSRID(ST_MakePoint(103.75, 1.37), 4326), ?)
                """, versionId, fetchedAt);
        jdbc.update("""
                insert into ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at,
                     outcome, http_status, records_received)
                values (?, 'DATAMALL', 'service:190:stop:44251', ?, ?, ?, 'SUCCESS', 200, 4)
                """, runId, runId.toString(), fetchedAt, fetchedAt);
        jdbc.update("""
                insert into ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload,
                     payload_checksum, fetched_at, ingested_at, http_status, classification)
                values (?, ?, 'DATAMALL', 'service:190:stop:44251', '{}'::jsonb, '{}'::jsonb,
                    repeat('b', 64), ?, ?, 200, 'OBSERVATION_ONLY')
                """, rawId, runId, fetchedAt, fetchedAt);
        insertArrival(versionId, runId, rawId, "190", 1, true, "1.3786545", "103.7584354", fetchedAt);
        insertArrival(versionId, runId, rawId, "190", 2, false, "1.3786550", "103.7584360", fetchedAt);
        insertArrival(versionId, runId, rawId, "190", 3, true, "0", "0", fetchedAt);
        insertArrival(versionId, runId, rawId, "972", 1, true, "1.3786560", "103.7584370", fetchedAt);

        var service190 = observations.findMapPositions("190", now, PageRequest.of(0, 50));
        assertThat(service190.getTotalElements()).isEqualTo(1);
        assertThat(service190.getContent().getFirst().getRawObservationId()).isEqualTo(rawId);
        assertThat(service190.getContent().getFirst().getLatitude().toPlainString()).isEqualTo("1.3786545");
        assertThat(observations.findMapPositions(null, now, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(2);
    }

    private void insertArrival(UUID versionId, UUID runId, UUID rawId, String serviceId, int visitOrder,
            boolean monitored, String latitude, String longitude, Timestamp fetchedAt) {
        jdbc.update("""
                insert into ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, stop_code,
                     service_id, visit_order, latitude, longitude, monitored,
                     fetched_at, ingested_at, record_checksum)
                values (?, ?, ?, ?, '44251', ?, ?, ?, ?, ?, ?, ?, repeat('c', 64))
                """, UUID.randomUUID(), rawId, runId, versionId, serviceId, visitOrder,
                new java.math.BigDecimal(latitude), new java.math.BigDecimal(longitude), monitored,
                fetchedAt, fetchedAt);
    }
}
