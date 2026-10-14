package com.csd.occ.diagnosis.integration;

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
        jdbc.execute("CREATE SCHEMA diagnosis");
        jdbc.execute("ALTER ROLE " + database.getUsername() + " SET search_path = diagnosis, public");
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").schemas("diagnosis")
                .target("001").load().migrate();
        jdbc.execute("""
insert into diagnosis.diagnosis_request (id,event_type,producer,event_id,schema_version,status,next_attempt_at,created_at,updated_at,diagnosis_id) values (
'00000000-0000-0000-0000-000000000001','BUS_BUNCHING','bus-bunching-detection','00000000-0000-0000-0000-000000000002',
'root-cause-diagnosis-v1','COMPLETED','2020-01-01','2020-01-01','2020-01-01','00000000-0000-0000-0000-000000000003')
""");
jdbc.execute("""
insert into diagnosis.root_cause_diagnosis values (
'00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','UNKNOWN',null,'legacy',0,0,'[]','[]','{}','{"legacy":true}',
repeat('a',64),'GOOGLE_GENAI','model','prompt','schema','2020-01-01')
""");
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").schemas("diagnosis").load().migrate();
        assertThat(jdbc.queryForObject("select id::text from diagnosis.root_cause_diagnosis where request_id='00000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("00000000-0000-0000-0000-000000000003");
assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='diagnosis' and table_name='diagnosis_request' and column_name='diagnosis_id'", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).defaultSchema("diagnosis").schemas("diagnosis").load().migrate().migrationsExecuted).isZero();
    }
}
