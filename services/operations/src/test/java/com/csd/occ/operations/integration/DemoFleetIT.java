package com.csd.occ.operations.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.operations.OperationsApplication;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = OperationsApplication.class, properties = {
        "occ.cognito.issuer-uri=https://issuer.example.test",
        "occ.cognito.app-client-id=synthetic-client",
        "spring.datasource.password=synthetic-operations",
        "springdoc.api-docs.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DemoFleetIT {
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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void controllerCanCreateADraftAndAssignedVehicleButDriverCannot() throws Exception {
        mvc.perform(post("/api/v1/demo-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"190\",\"directionId\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/demo-runs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"190\",\"directionId\":1}"))
                .andExpect(status().isForbidden());

        String runBody = mvc.perform(post("/api/v1/demo-runs")
                        .with(jwt().jwt(token -> token.subject("controller-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"190\",\"directionId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.classification").value("SIMULATION"))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(runBody, "$.id");
        String placement = """
                {"displayLabel":"Bus B","syntheticPlate":"DEMO-190-B",
                 "latitude":1.3786545,"longitude":103.7584354,
                 "driverDisplayName":"Demo Driver 02"}
                """;
        String vehicleBody = mvc.perform(post("/api/v1/demo-runs/{runId}/vehicles", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON).content(placement))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.classification").value("SIMULATION"))
                .andExpect(jsonPath("$.syntheticPlate").value("DEMO-190-B"))
                .andReturn().getResponse().getContentAsString();
        String vehicleId = JsonPath.read(vehicleBody, "$.vehicleId");
        mvc.perform(get("/api/v1/demo-runs/{runId}/vehicles/{vehicleId}", runId, vehicleId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverDisplayName").value("Demo Driver 02"));
        mvc.perform(get("/api/v1/demo-runs/{runId}/vehicles", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/demo-runs/{runId}/vehicles", runId).param("size", "201")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/demo-runs/{runId}/vehicles", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON).content(placement))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from operations.demo_assignment", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select created_by_sub from operations.demo_run where id = ?::uuid",
                String.class, runId)).isEqualTo("controller-1");
    }

    @Test
    void openApiDescribesTheProtectedDemoFleetEndpoints() throws Exception {
        String document = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(document).contains("/api/v1/demo-runs", "placeDemoVehicle", "bearerAuth",
                "DemoVehicleResponse");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "generated-openapi.json"), document);
    }
}
