package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseReductionUpgradeIT {
    @Container static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"));

    @Test void populatedLegacySchemaUpgradesWithoutLosingFunctionalState() {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbc.execute("CREATE SCHEMA ingestion");
        jdbc.execute("ALTER ROLE " + database.getUsername() + " SET search_path = ingestion, public");
        Flyway.configure().dataSource(source).defaultSchema("ingestion").schemas("ingestion")
                .target("014").load().migrate();
        jdbc.execute("insert into ingestion.reference_dataset_version values ('00000000-0000-0000-0000-000000000001','BUS_TOPOLOGY','legacy',repeat('a',64),'ACTIVE','2020-01-01','2020-01-01')");
jdbc.execute("insert into ingestion.bus_stop values ('00000000-0000-0000-0000-000000000001','44009','Road','Stop',ST_SetSRID(ST_MakePoint(103.8,1.3),4326),'2020-01-01')");
jdbc.execute("insert into ingestion.bus_service values ('00000000-0000-0000-0000-000000000001','190','SBST','TRUNK','2020-01-01')");
jdbc.execute("insert into ingestion.bus_route_stop values ('00000000-0000-0000-0000-000000000001','190','SBST',1,1,'44009',0,'2020-01-01')");
        jdbc.execute("insert into ingestion.demo_run (id,scenario_key,scenario_version,seed,status,started_at) values ('00000000-0000-0000-0000-000000000002','legacy','v1',190,'CLOSED','2020-01-01')");
        jdbc.execute("insert into ingestion.demo_vehicle_assignment values ('00000000-0000-0000-0000-000000000002','demo-190-b','190',1,'SBST','SBS5898U','DD','legacy-captain')");
        jdbc.execute("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome, classification,
                     demo_run_id)
                VALUES ('00000000-0000-0000-0000-000000000012', 'TRAFFIC_INCIDENT_DEMO', 'DEMO',
                        'legacy-traffic-poll', '2020-01-01', '2020-01-01', 'SUCCESS', 'SIMULATION',
                        '00000000-0000-0000-0000-000000000002')
                """);
        jdbc.execute("""
                INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status, classification, demo_run_id)
                VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000012',
                        'TRAFFIC_INCIDENT_DEMO', 'legacy-traffic', '{}', '{}', repeat('a', 64), '2020-01-01',
                        '2020-01-01', 200, 'SIMULATION', '00000000-0000-0000-0000-000000000002')
                """);
        jdbc.execute("""
                INSERT INTO ingestion.traffic_incident_observation
                    (id, raw_observation_id, poll_run_id, source_incident_key, incident_type, latitude, longitude,
                     message, observed_at, fetched_at, ingested_at, classification, demo_run_id)
                VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000011',
                        '00000000-0000-0000-0000-000000000012', repeat('a', 64), 'Vehicle Breakdown',
                        1.300, 103.800, 'Legacy traffic report', '2020-01-01', '2020-01-01', '2020-01-01',
                        'SIMULATION', '00000000-0000-0000-0000-000000000002')
                """);
        Flyway.configure().dataSource(source).defaultSchema("ingestion").schemas("ingestion").target("020").load().migrate();
        jdbc.execute("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, completed_at, outcome, classification,
                     demo_run_id)
                VALUES ('00000000-0000-0000-0000-000000000013', 'FLOOD_DEMO', 'DEMO', 'legacy-flood-poll',
                        '2020-01-01', '2020-01-01', 'SUCCESS', 'SIMULATION',
                        '00000000-0000-0000-0000-000000000002')
                """);
        jdbc.execute("""
                INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status, classification, demo_run_id)
                VALUES ('00000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000013',
                        'FLOOD_DEMO', 'legacy-flood', '{}', '{}', repeat('b', 64), '2020-01-01', '2020-01-01',
                        200, 'SIMULATION', '00000000-0000-0000-0000-000000000002')
                """);
        jdbc.execute("""
                INSERT INTO ingestion.traffic_incident_observation
                    (id, raw_observation_id, poll_run_id, source_incident_key, incident_type, message, observed_at,
                     fetched_at, ingested_at, classification, demo_run_id, hazard_kind, hazard_facts)
                VALUES ('00000000-0000-0000-0000-000000000015', '00000000-0000-0000-0000-000000000014',
                        '00000000-0000-0000-0000-000000000013', repeat('b', 64), 'FLOOD', 'Legacy flood report',
                        '2020-01-01', '2020-01-01', '2020-01-01', 'SIMULATION',
                        '00000000-0000-0000-0000-000000000002', 'FLOOD', '{"hazardKind":"FLOOD"}'::jsonb)
                """);
        Flyway.configure().dataSource(source).defaultSchema("ingestion").schemas("ingestion").load().migrate();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ingestion.traffic_incident_observation
                WHERE id = '00000000-0000-0000-0000-000000000010' AND latitude IS NOT NULL AND longitude IS NOT NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ingestion.traffic_incident_observation
                WHERE id = '00000000-0000-0000-0000-000000000015'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT hazard_kind FROM ingestion.hazard_observation
                WHERE id = '00000000-0000-0000-0000-000000000015'
                """, String.class)).isEqualTo("FLOOD");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'ingestion' AND table_name = 'traffic_incident_observation'
                  AND column_name IN ('hazard_kind', 'hazard_facts')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.bus_route_stop", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select bus_id from ingestion.demo_vehicle_assignment", String.class)).isEqualTo("demo-190-b");
        jdbc.execute("update ingestion.demo_vehicle_assignment set captain_user_id='00000000-0000-0000-0000-000000000003'");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute("insert into ingestion.demo_vehicle_assignment (demo_run_id,trip_id,service_id,direction_id,operator,vehicle_registration_number,vehicle_type,captain_code,bus_id,captain_user_id) values ('00000000-0000-0000-0000-000000000002','other','190',1,'SBST','OTHER','DD','other','other','00000000-0000-0000-0000-000000000003')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute("insert into ingestion.demo_vehicle_assignment (demo_run_id,trip_id,service_id,direction_id,operator,vehicle_registration_number,vehicle_type,captain_code,bus_id) values ('00000000-0000-0000-0000-000000000002','other','190',1,'SBST','OTHER','DD','other','demo-190-b')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
assertThat(jdbc.queryForObject("select imported_at::text from ingestion.reference_dataset_version", String.class)).startsWith("2020-01-01");
assertThat(jdbc.queryForObject("select to_regclass('ingestion.bunching_snapshot') is null", Boolean.class)).isTrue();
assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='ingestion' and table_name in ('bus_stop','bus_service','bus_route_stop') and column_name='imported_at'", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).defaultSchema("ingestion").schemas("ingestion").load().migrate().migrationsExecuted).isZero();
    }
}
