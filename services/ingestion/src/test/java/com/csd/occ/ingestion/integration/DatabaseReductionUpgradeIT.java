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
        Flyway.configure().dataSource(source).defaultSchema("ingestion").schemas("ingestion").load().migrate();
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
