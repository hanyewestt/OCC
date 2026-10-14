package com.csd.occ.bunching.integration;

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
        jdbc.execute("CREATE SCHEMA bunching");
        jdbc.execute("ALTER ROLE " + database.getUsername() + " SET search_path = bunching, public");
        Flyway.configure().dataSource(source).defaultSchema("bunching").schemas("bunching")
                .target("11").load().migrate();
        jdbc.execute("insert into bunching.source_retry (raw_observation_id,next_attempt_at) values ('00000000-0000-0000-0000-000000000001','2020-01-01')");
jdbc.execute("insert into bunching.source_rejection (raw_observation_id,rejected_at,reason) values ('00000000-0000-0000-0000-000000000001','2020-01-01','INVALID')");
jdbc.execute("insert into bunching.source_retry (raw_observation_id,next_attempt_at,feed_scope,demo_run_id) values ('00000000-0000-0000-0000-000000000002','2020-01-01','DEMO:00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000003')");
jdbc.execute("insert into bunching.alert_state values ('190','44009',1,'2020-01-01','00000000-0000-0000-0000-000000000004')");
jdbc.execute("insert into bunching.demo_alert_state (demo_run_id,service_id,stop_code,direction_id,last_fetched_at,pending_headway_seconds,pending_detected_at,pending_raw_observation_id) values ('00000000-0000-0000-0000-000000000003','190','44009',1,'2020-01-01',60,'2020-01-01','00000000-0000-0000-0000-000000000002')");
jdbc.execute("update bunching.ingestion_feed_cursor set last_sequence=42");
        Flyway.configure().dataSource(source).defaultSchema("bunching").schemas("bunching").load().migrate();
        assertThat(jdbc.queryForObject("select status from bunching.source_processing_state where raw_observation_id='00000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("REJECTED");
assertThat(jdbc.queryForObject("select status from bunching.source_processing_state where raw_observation_id='00000000-0000-0000-0000-000000000002'", String.class)).isEqualTo("DEFERRED");
assertThat(jdbc.queryForObject("select last_sequence from bunching.consumer_state", Long.class)).isEqualTo(42);
assertThat(jdbc.queryForObject("select count(*) from bunching.alert_processing_state", Integer.class)).isEqualTo(2);
assertThat(jdbc.queryForObject("select pending_headway_seconds from bunching.alert_processing_state where demo_run_id is not null", Integer.class)).isEqualTo(60);
        assertThat(Flyway.configure().dataSource(source).defaultSchema("bunching").schemas("bunching").load().migrate().migrationsExecuted).isZero();
    }
}
