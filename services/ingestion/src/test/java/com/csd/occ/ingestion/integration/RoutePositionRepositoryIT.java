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
    void mapsOpenAndCircularRoutesWithoutDuplicatingRepeatedStops() {
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
        jdbc.update("""
                INSERT INTO ingestion.bus_service (reference_version_id, service_id, operator, category)
                VALUES (?, '972', 'SMRT', 'TRUNK')
                """, VERSION_ID);
        insertStop("A", 103.800, 1.300);
        insertStop("B", 103.810, 1.300);
        insertStop("C", 103.820, 1.300);
        insertRouteStop("190", 1, 1, "A", 0.0);
        insertRouteStop("190", 1, 2, "B", 1.1);
        insertRouteStop("190", 1, 3, "C", 2.2);
        insertRouteStop("190", 2, 1, "C", 0.0);
        insertRouteStop("190", 2, 2, "A", 2.2);
        insertPollTarget("190", "A", now);
        insertPollTarget("190", "B", now);
        UUID observationId = UUID.randomUUID();
        UUID snapshotId = insertObservation(observationId, pollCycleId, "B", 103.810,
                now.plusSeconds(120), now.minusSeconds(5));
        insertObservation(UUID.randomUUID(), pollCycleId, "A", 103.800,
                now.plusSeconds(180), now.minusSeconds(4));
        UUID incompleteCycleObservationId = UUID.randomUUID();
        insertObservation(incompleteCycleObservationId, UUID.randomUUID(), "B", 103.819,
                now.plusSeconds(60), now.minusSeconds(1));

        var routes = repository.findRoutesWithObservations("190", now.minusSeconds(120), now);

        assertThat(routes).hasSize(2);
        var direction1 = routes.stream().filter(route -> route.directionId() == 1).findFirst().orElseThrow();
        var direction2 = routes.stream().filter(route -> route.directionId() == 2).findFirst().orElseThrow();
        assertThat(direction1.routeLengthM()).isBetween(2_100.0, 2_300.0);
        assertThat(direction1.readings()).hasSize(1);
        assertThat(direction1.readings()).extracting(reading -> reading.observationId())
                .doesNotContain(incompleteCycleObservationId);
        assertThat(direction1.readings().getFirst().observationId()).isEqualTo(observationId);
        assertThat(direction1.readings().getFirst().snapshotId()).isEqualTo(snapshotId);
        assertThat(direction1.readings().getFirst().pollCycleId()).isEqualTo(pollCycleId);
        assertThat(direction1.selectedPollCycleId()).isEqualTo(pollCycleId);
        assertThat(direction1.readings().getFirst().stopCode()).isEqualTo("B");
        assertThat(direction1.readings().getFirst().stopSequence()).isEqualTo(2);
        assertThat(Math.abs(Duration.between(now.plusSeconds(120),
                direction1.readings().getFirst().estimatedArrivalAt()).toNanos())).isLessThanOrEqualTo(1_000L);
        assertThat(direction1.readings().getFirst().distanceAlongRouteM())
                .isCloseTo(direction1.routeLengthM() / 2, org.assertj.core.data.Offset.offset(2.0));
        assertThat(direction1.readings().getFirst().projectionCandidates()).hasSize(2);
        assertThat(direction2.readings()).isEmpty();

        UUID emptyCompleteCycleId = UUID.randomUUID();
        insertCompletedPollRun(emptyCompleteCycleId, "190", "A", now);
        insertCompletedPollRun(emptyCompleteCycleId, "190", "B", now);

        var routesAfterEmptyCycle = repository.findRoutesWithObservations("190", now.minusSeconds(120), now);

        assertThat(routesAfterEmptyCycle).hasSize(2);
        assertThat(routesAfterEmptyCycle).allSatisfy(route ->
                assertThat(route.selectedPollCycleId()).isEqualTo(emptyCompleteCycleId));
        assertThat(routesAfterEmptyCycle.stream()
                .filter(route -> route.directionId() == 1).findFirst().orElseThrow().readings())
                .extracting(reading -> reading.pollCycleId()).containsOnly(pollCycleId);

        UUID failedCompleteCycleId = UUID.randomUUID();
        insertCompletedPollRun(failedCompleteCycleId, "190", "A", "FAILED", now.plusSeconds(1));
        insertCompletedPollRun(failedCompleteCycleId, "190", "B", "FAILED", now.plusSeconds(1));

        var routesAfterFailedCycle = repository.findRoutesWithObservations(
                "190", now.minusSeconds(120), now.plusSeconds(1));

        assertThat(routesAfterFailedCycle).hasSize(2);
        assertThat(routesAfterFailedCycle).allSatisfy(route ->
                assertThat(route.selectedPollCycleId()).isEqualTo(emptyCompleteCycleId));

        insertStop("D", 103.830, 1.300);
        insertStop("E", 103.840, 1.300);
        insertStop("F", 103.840, 1.310);
        insertRouteStop("972", 1, 1, "D", 0.0);
        insertRouteStop("972", 1, 2, "E", 1.1);
        insertRouteStop("972", 1, 3, "F", 2.2);
        insertRouteStop("972", 1, 4, "D", 3.8);
        insertPollTarget("972", "D", now);
        UUID circularObservationId = UUID.randomUUID();
        insertObservation(circularObservationId, pollCycleId, "972", "D", 1, 103.830,
                "D", "D", 2, now.plusSeconds(240), now.minusSeconds(3));

        var circularRoutes = repository.findRoutesWithObservations("972", now.minusSeconds(120), now);

        assertThat(circularRoutes).hasSize(1);
        var circular = circularRoutes.getFirst();
        assertThat(circular.circular()).isTrue();
        assertThat(circular.referenceLengthM()).isEqualTo(3_800.0);
        assertThat(circular.readings()).hasSize(1);
        var circularReading = circular.readings().getFirst();
        assertThat(circularReading.observationId()).isEqualTo(circularObservationId);
        assertThat(circularReading.stopReferenceDistancesM()).containsExactly(0.0, 3_800.0);
        assertThat(circularReading.projectionCandidates()).hasSize(3);
        assertThat(circularReading.projectionCandidates())
                .extracting(candidate -> candidate.segmentEndSequence())
                .containsExactly(2, 3, 4);
        assertThat(circularReading.projectionCandidates().getFirst().distanceAlongRouteM())
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(2.0));
        assertThat(circularReading.projectionCandidates().getLast().distanceAlongRouteM())
                .isCloseTo(circular.routeLengthM(), org.assertj.core.data.Offset.offset(2.0));
    }

    private void insertStop(String code, double longitude, double latitude) {
        jdbc.update("""
                INSERT INTO ingestion.bus_stop (reference_version_id, stop_code, road_name, description, location)
                VALUES (?, ?, 'Fixture Road', 'Fixture stop', ST_SetSRID(ST_MakePoint(?, ?),4326))
                """,
                VERSION_ID, code, longitude, latitude);
    }

    private void insertRouteStop(String serviceId, int direction, int sequence, String stopCode,
                                 double distanceKm) {
        jdbc.update("""
                INSERT INTO ingestion.bus_route_stop
                    (reference_version_id, service_id, operator, direction_id, stop_sequence, stop_code,
                     distance_km)
                VALUES (?, ?, 'SMRT', ?, ?, ?, ?)
                """,
                VERSION_ID, serviceId, direction, sequence, stopCode, distanceKm);
    }

    private void insertPollTarget(String serviceId, String stopCode, Instant now) {
        String scopeKey = "service:" + serviceId + ":stop:" + stopCode;
        jdbc.update("""
                INSERT INTO ingestion.poll_target
                    (source, scope_key, stop_code, service_id, enabled, poll_interval_seconds,
                     created_at, updated_at)
                VALUES ('DATAMALL', ?, ?, ?, true, 60, ?, ?)
                """, scopeKey, stopCode, serviceId, Timestamp.from(now), Timestamp.from(now));
    }

    private UUID insertObservation(UUID observationId, UUID pollCycleId, String stopCode, double longitude,
                                   Instant estimatedArrivalAt, Instant fetchedAt) {
        return insertObservation(observationId, pollCycleId, "190", stopCode, null, longitude,
                null, null, null, estimatedArrivalAt, fetchedAt);
    }

    private UUID insertObservation(UUID observationId, UUID pollCycleId, String serviceId, String stopCode,
                                   Integer directionId, double longitude, String originCode,
                                   String destinationCode, Integer visitNumber,
                                   Instant estimatedArrivalAt, Instant fetchedAt) {
        UUID pollRunId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String scopeKey = "service:" + serviceId + ":stop:" + stopCode;
        jdbc.update("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome,
                     route_position_poll_cycle_id)
                VALUES (?, 'DATAMALL', ?, ?, ?, ?, 'SUCCESS', ?)
                """, pollRunId, scopeKey, pollRunId.toString(), Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt), pollCycleId);
        jdbc.update("""
                INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status)
                VALUES (?, ?, 'DATAMALL', ?, '{}'::jsonb, '{}'::jsonb, ?, ?, ?, 200)
                """, snapshotId, pollRunId, scopeKey, "b".repeat(64), Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt));
        jdbc.update("""
                INSERT INTO ingestion.bus_arrival_observation
                    (id, raw_observation_id, poll_run_id, reference_version_id, service_id, stop_code,
                     operator, direction_id, visit_order, estimated_arrival_at, classification, latitude,
                     longitude, origin_code, destination_code, visit_number, fetched_at, ingested_at,
                     record_checksum)
                VALUES (?, ?, ?, ?, ?, ?, 'SMRT', ?, 1, ?, 'OBSERVATION_ONLY', 1.300, ?, ?, ?, ?, ?, ?, ?)
                """, observationId, snapshotId, pollRunId, VERSION_ID, serviceId, stopCode, directionId,
                Timestamp.from(estimatedArrivalAt), longitude, originCode, destinationCode, visitNumber,
                Timestamp.from(fetchedAt), Timestamp.from(fetchedAt), "c".repeat(64));
        return snapshotId;
    }

    private void insertCompletedPollRun(UUID pollCycleId, String serviceId, String stopCode, Instant completedAt) {
        insertCompletedPollRun(pollCycleId, serviceId, stopCode, "SUCCESS", completedAt);
    }

    private void insertCompletedPollRun(UUID pollCycleId, String serviceId, String stopCode, String outcome,
                                        Instant completedAt) {
        UUID pollRunId = UUID.randomUUID();
        String scopeKey = "service:" + serviceId + ":stop:" + stopCode;
        jdbc.update("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome,
                     route_position_poll_cycle_id)
                VALUES (?, 'DATAMALL', ?, ?, ?, ?, ?, ?)
                """, pollRunId, scopeKey, pollRunId.toString(), Timestamp.from(completedAt),
                Timestamp.from(completedAt), outcome, pollCycleId);
    }
}
