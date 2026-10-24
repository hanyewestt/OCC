package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.ingestion.infrastructure.persistence.PostgisRoutePositionRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RoutePositionRepositoryIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ").withPassword("synthetic");

    private static JdbcTemplate jdbc;
    private static PostgisRoutePositionRepository repository;
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void prepareSpatialSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbc.execute("CREATE SCHEMA ingestion");
        Flyway.configure().dataSource(dataSource).defaultSchema("ingestion").schemas("ingestion")
                .locations("classpath:db/migration").load().migrate();
        repository = new PostgisRoutePositionRepository(jdbc);
    }

    @Test
    void snapsCoordinateToPostgisRouteAndDoesNotGuessASharedStopsDirection() {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        UUID pollCycleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ingestion.reference_dataset_version
                    (id, dataset, version_key, checksum, status, imported_at, activated_at)
                VALUES (?, 'BUS_TOPOLOGY', 'fixture', ?, 'ACTIVE', ?, ?)
                """, VERSION_ID, "a".repeat(64), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO ingestion.bus_service (reference_version_id, service_id, operator, category)
                VALUES (?, '190', 'SMRT', 'TRUNK')
                """, VERSION_ID);
        insertStop("A", 103.800, 1.300);
        insertStop("B", 103.810, 1.300);
        insertStop("C", 103.820, 1.300);
        insertRouteStop(1, 1, "A");
        insertRouteStop(1, 2, "B");
        insertRouteStop(1, 3, "C");
        insertRouteStop(2, 1, "C");
        insertRouteStop(2, 2, "A");
        UUID observationId = UUID.randomUUID();
        UUID snapshotId = insertObservation(observationId, pollCycleId, "B", 103.810,
                now.plusSeconds(120), now.minusSeconds(5));
        insertObservation(UUID.randomUUID(), pollCycleId, "A", 103.800,
                now.plusSeconds(180), now.minusSeconds(4));

        var routes = repository.findRoutesWithObservations("190", now.minusSeconds(120), now);

        assertThat(routes).hasSize(2);
        var direction1 = routes.stream().filter(route -> route.directionId() == 1).findFirst().orElseThrow();
        var direction2 = routes.stream().filter(route -> route.directionId() == 2).findFirst().orElseThrow();
        assertThat(direction1.routeLengthM()).isBetween(2_100.0, 2_300.0);
        assertThat(direction1.readings()).hasSize(1);
        assertThat(direction1.readings().getFirst().observationId()).isEqualTo(observationId);
        assertThat(direction1.readings().getFirst().snapshotId()).isEqualTo(snapshotId);
        assertThat(direction1.readings().getFirst().pollCycleId()).isEqualTo(pollCycleId);
        assertThat(direction1.readings().getFirst().stopCode()).isEqualTo("B");
        assertThat(direction1.readings().getFirst().stopSequence()).isEqualTo(2);
        assertThat(Math.abs(Duration.between(now.plusSeconds(120),
                direction1.readings().getFirst().estimatedArrivalAt()).toNanos())).isLessThanOrEqualTo(1_000L);
        assertThat(direction1.readings().getFirst().distanceAlongRouteM())
                .isCloseTo(direction1.routeLengthM() / 2, org.assertj.core.data.Offset.offset(2.0));
        assertThat(direction2.readings()).isEmpty();
    }

    private void insertStop(String code, double longitude, double latitude) {
        jdbc.update("""
                INSERT INTO ingestion.bus_stop (reference_version_id, stop_code, road_name, description, location)
                VALUES (?, ?, 'Fixture Road', 'Fixture stop', ST_SetSRID(ST_MakePoint(?, ?),4326))
                """,
                VERSION_ID, code, longitude, latitude);
    }

    private void insertRouteStop(int direction, int sequence, String stopCode) {
        jdbc.update("""
                INSERT INTO ingestion.bus_route_stop
                    (reference_version_id, service_id, operator, direction_id, stop_sequence, stop_code)
                VALUES (?, '190', 'SMRT', ?, ?, ?)
                """,
                VERSION_ID, direction, sequence, stopCode);
    }

    private UUID insertObservation(UUID observationId, UUID pollCycleId, String stopCode, double longitude,
                                   Instant estimatedArrivalAt, Instant fetchedAt) {
        UUID pollRunId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome,
                     route_position_poll_cycle_id)
                VALUES (?, 'DATAMALL', ?, ?, ?, ?, 'SUCCESS', ?)
                """, pollRunId, "stop:" + stopCode, pollRunId.toString(), Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt), pollCycleId);
        jdbc.update("""
                INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status)
                VALUES (?, ?, 'DATAMALL', ?, '{}'::jsonb, '{}'::jsonb, ?, ?, ?, 200)
                """, snapshotId, pollRunId, "stop:" + stopCode, "b".repeat(64), Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt));
        jdbc.update("""
                INSERT INTO ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, service_id, stop_code,
                     operator, direction_id, visit_order, estimated_arrival_at, classification, latitude,
                     longitude, fetched_at, ingested_at, record_checksum)
                VALUES (?, ?, ?, ?, '190', ?, 'SMRT', null, 1, ?, 'OBSERVATION_ONLY', 1.300, ?, ?, ?, ?)
                """, observationId, snapshotId, pollRunId, VERSION_ID, stopCode, Timestamp.from(estimatedArrivalAt),
                longitude, Timestamp.from(fetchedAt), Timestamp.from(fetchedAt), "c".repeat(64));
        return snapshotId;
    }
}
