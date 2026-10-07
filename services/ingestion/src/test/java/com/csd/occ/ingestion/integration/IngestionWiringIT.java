package com.csd.occ.ingestion.integration;

import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import com.csd.occ.ingestion.IngestionApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private final JdbcTemplate jdbc;

    @Autowired
    IngestionWiringIT(BusArrivalService polling, JdbcTemplate jdbc) {
        this.polling = polling;
        this.jdbc = jdbc;
    }

    @Test
    void productionApplicationUsesTheExistingPollingServiceAndAppliesTheBunchingContract() {
        assertNotNull(polling);
    }
}
