package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.ingestion.infrastructure.persistence.PostgisRoutePositionRepository;
import java.time.Instant;
import java.util.UUID;
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
        jdbc.execute("CREATE TABLE ingestion.reference_dataset_version (id uuid, dataset varchar(40), status varchar(20))");
        jdbc.execute("CREATE TABLE ingestion.bus_stop (reference_version_id uuid, stop_code varchar(12), location geometry(Point,4326))");
        jdbc.execute("CREATE TABLE ingestion.bus_route_stop (reference_version_id uuid, service_id varchar(20), operator varchar(40), direction_id smallint, stop_sequence integer, stop_code varchar(12))");
        jdbc.execute("CREATE TABLE ingestion.bus_arrival_observation (id uuid, service_id varchar(20), stop_code varchar(12), direction_id smallint, classification varchar(30), latitude numeric(10,7), longitude numeric(10,7), fetched_at timestamptz)");
        repository = new PostgisRoutePositionRepository(jdbc);
    }

    @Test
    void snapsCoordinateToPostgisRouteAndDoesNotGuessASharedStopsDirection() {
        jdbc.update("INSERT INTO ingestion.reference_dataset_version VALUES (?, 'BUS_TOPOLOGY', 'ACTIVE')", VERSION_ID);
        insertStop("A", 103.800, 1.300);
        insertStop("B", 103.810, 1.300);
        insertStop("C", 103.820, 1.300);
        insertRouteStop(1, 1, "A");
        insertRouteStop(1, 2, "B");
        insertRouteStop(1, 3, "C");
        insertRouteStop(2, 1, "C");
        insertRouteStop(2, 2, "A");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ingestion.bus_arrival_observation VALUES (?, '190', 'B', null, 'OBSERVATION_ONLY', 1.300, 103.810, ?)",
                UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(5)));
        jdbc.update("INSERT INTO ingestion.bus_arrival_observation VALUES (?, '190', 'A', null, 'OBSERVATION_ONLY', 1.300, 103.800, ?)",
                UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(4)));

        var routes = repository.findRoutesWithObservations("190", now.minusSeconds(120), now);

        assertThat(routes).hasSize(2);
        var direction1 = routes.stream().filter(route -> route.directionId() == 1).findFirst().orElseThrow();
        var direction2 = routes.stream().filter(route -> route.directionId() == 2).findFirst().orElseThrow();
        assertThat(direction1.routeLengthM()).isBetween(2_100.0, 2_300.0);
        assertThat(direction1.readings()).hasSize(1);
        assertThat(direction1.readings().getFirst().distanceAlongRouteM())
                .isCloseTo(direction1.routeLengthM() / 2, org.assertj.core.data.Offset.offset(2.0));
        assertThat(direction2.readings()).isEmpty();
    }

    private void insertStop(String code, double longitude, double latitude) {
        jdbc.update("INSERT INTO ingestion.bus_stop VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?),4326))",
                VERSION_ID, code, longitude, latitude);
    }

    private void insertRouteStop(int direction, int sequence, String stopCode) {
        jdbc.update("INSERT INTO ingestion.bus_route_stop VALUES (?, '190', 'SMRT', ?, ?, ?)",
                VERSION_ID, direction, sequence, stopCode);
    }
}
