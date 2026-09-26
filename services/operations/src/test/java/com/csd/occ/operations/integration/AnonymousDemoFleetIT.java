package com.csd.occ.operations.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.operations.OperationsApplication;
import com.csd.occ.operations.domain.LiveMapPort;
import com.csd.occ.operations.domain.MapReferencePort;
import com.csd.occ.operations.domain.RouteReferencePort;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = OperationsApplication.class, properties = {
        "occ.cognito.enabled=false", "occ.cognito.issuer-uri=", "occ.cognito.app-client-id=",
        "occ.ingestion-topology.base-url=http://ingestion.test", "occ.track-inference.enabled=false",
        "spring.datasource.password=synthetic-operations", "springdoc.api-docs.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AnonymousDemoFleetIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withEnv("OCC_INGESTION_DB_PASSWORD", "synthetic-ingestion")
            .withEnv("OCC_OPERATIONS_DB_PASSWORD", "synthetic-operations")
            .withEnv("OCC_PLANNING_DB_PASSWORD", "synthetic-planning")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/local/postgres/init/01_schemas_and_roles.sh").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schemas_and_roles.sh");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RouteReferencePort routes;
    @MockitoBean LiveMapPort live;
    @MockitoBean MapReferencePort mapReference;

    @Test void mapSelectionsAndArrivalsAreAvailableWithoutLogin() throws Exception {
        var stop = new MapReferencePort.Stop("44251", "Demo Road", "Demo Stop", new BigDecimal("1.37"), new BigDecimal("103.76"), 1);
        when(mapReference.services(0, 50)).thenReturn(new MapReferencePort.ServicePage(
                List.of(new MapReferencePort.Service("190", "SMRT", "TRUNK")), 0, 50, 1, 1));
        when(mapReference.route("190")).thenReturn(new MapReferencePort.Route("190", List.of(
                new MapReferencePort.Operator("SMRT", List.of(new MapReferencePort.Direction(1, List.of(stop)))))));
        when(mapReference.arrivals("190", "44251", 0, 50)).thenReturn(new MapReferencePort.ArrivalPage(
                List.of(new MapReferencePort.Arrival("44251", "190", null, 1,
                        java.time.Instant.parse("2026-09-26T08:10:00Z"), java.time.Instant.parse("2026-09-26T08:00:00Z"), 5L)), 0, 50, 1, 1));
        mvc.perform(get("/api/v1/map/services")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].serviceId").value("190"));
        mvc.perform(get("/api/v1/map/routes/190")).andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].directions[0].stops[0].description").value("Demo Stop"));
        mvc.perform(get("/api/v1/map/arrivals").param("serviceId", "190").param("stopCode", "44251"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].estimatedArrivalAt").value("2026-09-26T08:10:00Z"));
        mvc.perform(get("/api/v1/map/services").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/map/arrivals").param("serviceId", "190")).andExpect(status().isBadRequest());
    }

    @Test void draftLifecycleNeedsNeitherTokenNorCognitoConfiguration() throws Exception {
        when(routes.route("190")).thenReturn(Optional.of(new RouteReferencePort.Route("190",List.of(
                new RouteReferencePort.Direction("SMRT",1,List.of(
                        new RouteReferencePort.Stop("44251",8,new BigDecimal("1.3786545"),new BigDecimal("103.7625711"))))))));
        String created=mvc.perform(post("/api/v1/demo-runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"190\",\"directionId\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(created,"$.id");
        mvc.perform(get("/api/v1/demo-runs/"+id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/demo-runs/"+id+"/route")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/demo-runs/"+id+"/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayLabel\":\"Bus B\",\"driverDisplayName\":\"Demo Driver\",\"latitude\":1.3786545,\"longitude\":103.7625711}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/demo-runs/"+id+"/vehicles")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        assertThat(jdbc.queryForObject("select created_by_sub from operations.demo_run where id = ?",String.class,java.util.UUID.fromString(id)))
                .isEqualTo("prototype-local");
        mvc.perform(get("/api/v1/map/settings")).andExpect(status().isOk()).andExpect(jsonPath("$.cognitoEnabled").value(false));
        String contract=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<?>>read(contract,"$.paths['/api/v1/demo-runs'].post.security")).isEmpty();
        Files.writeString(Path.of("target","generated-openapi-anonymous.json"),contract);
    }
}
