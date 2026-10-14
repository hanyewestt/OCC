package com.csd.occ.planning.recommendationengine.integration;

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
        jdbc.execute("CREATE SCHEMA recommendation");
        jdbc.execute("ALTER ROLE " + database.getUsername() + " SET search_path = recommendation, public");
        Flyway.configure().dataSource(source).defaultSchema("recommendation").schemas("recommendation")
                .target("002").load().migrate();
        jdbc.execute("""
insert into recommendation.recommendation_record values (
'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',repeat('a',64),
'00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
'SIMULATION','190',1,'44009','HOLD','RECOMMENDED','legacy rationale','BUS','44009',120,
'{"bunchingEvent":{"bunchingEventId":"00000000-0000-0000-0000-000000000003","directionId":1}}','{}','{"adjustmentSeconds":120}','[]','2020-01-01')
""");
jdbc.execute("""
insert into recommendation.model_invocation_record values (
'00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000001','GEMINI','model','prompt','schema',null,
'PROVIDER_FAILURE',null,null,'TIMEOUT',true,null,null,null,'2020-01-01','2020-01-01',0,'2020-01-01')
""");
        Flyway.configure().dataSource(source).defaultSchema("recommendation").schemas("recommendation").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='recommendation' and table_name='recommendation_record'", Integer.class)).isEqualTo(9);
assertThat(jdbc.queryForObject("select correlation_id::text from recommendation.model_invocation_record", String.class)).isEqualTo("00000000-0000-0000-0000-000000000001");
assertThat(jdbc.queryForObject("select outcome from recommendation.model_invocation_record", String.class)).isEqualTo("PROVIDER_FAILURE");
        assertThat(Flyway.configure().dataSource(source).defaultSchema("recommendation").schemas("recommendation").load().migrate().migrationsExecuted).isZero();
    }
}
