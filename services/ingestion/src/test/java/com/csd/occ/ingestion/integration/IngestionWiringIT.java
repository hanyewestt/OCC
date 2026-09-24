package com.csd.occ.ingestion.integration;

import com.csd.occ.ingestion.domain.ArrivalPollingService;
import com.csd.occ.ingestion.domain.ArrivalTelemetryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import sg.edu.smu.occ.ingestion.IngestionApplication;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = IngestionApplication.class, properties = {
        "spring.main.web-application-type=none", "spring.datasource.password=synthetic-only",
        "datamall.account-key=synthetic-only", "occ.ingestion.seed-enabled=false",
        "occ.ingestion.arrival.enabled=false", "occ.ingestion.arrival.account-key=synthetic-only",
        "occ.ingestion.arrival.stale-after-seconds=120"
})
@Testcontainers(disabledWithoutDocker = true)
class IngestionWiringIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withInitScript("db/audit-schema.sql");

    private final ArrivalPollingService polling;
    private final ArrivalTelemetryPort telemetry;
    private final JdbcTemplate jdbc;

    @Autowired
    IngestionWiringIT(ArrivalPollingService polling, ArrivalTelemetryPort telemetry, JdbcTemplate jdbc) {
        this.polling = polling;
        this.telemetry = telemetry;
        this.jdbc = jdbc;
    }

    @Test
    void productionApplicationDiscoversPollingBeansAndAppliesAuditMigration() {
        assertNotNull(polling);
        assertTrue(telemetry.targets().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ingestion.telemetry_audit", Integer.class));
    }
}
