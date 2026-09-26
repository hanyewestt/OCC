package com.csd.occ.operations.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.operations.OperationsApplication;
import com.csd.occ.operations.domain.RouteReferencePort;
import com.csd.occ.operations.domain.LiveMapPort;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
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
        "occ.cognito.enabled=true",
        "occ.ingestion-topology.base-url=http://ingestion.test",
        "spring.datasource.password=synthetic-operations",
        "springdoc.api-docs.enabled=true"
        , "occ.track-inference.enabled=false"
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
    @MockitoBean RouteReferencePort routeReference;
    @MockitoBean LiveMapPort liveMap;
    @Autowired com.csd.occ.operations.domain.TrackInferenceService inference;

    @Test
    void persistentTracksAreIdempotentAmbiguousAndExpire() throws Exception {
        jdbc.update("delete from operations.inferred_observation_link");
        jdbc.update("delete from operations.inferred_vehicle_track");
        when(routeReference.route("190")).thenReturn(Optional.of(new RouteReferencePort.Route("190", List.of(
                new RouteReferencePort.Direction("SMRT",1,List.of(
                        new RouteReferencePort.Stop("A",1,new BigDecimal("1.35"),new BigDecimal("103.80")),
                        new RouteReferencePort.Stop("B",2,new BigDecimal("1.35"),new BigDecimal("103.82"))))))));
        var now=java.time.Instant.now();
        var first=trackObservation("A","103.805",now.minusSeconds(70));
        var duplicate=trackObservation("B","103.8051",now.minusSeconds(70));
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(List.of(first,duplicate),0,200,2,1));
        inference.refresh(); inference.refresh();
        var track=inference.current("190").getFirst(); var id=track.getId();
        assertThat(inference.current("190")).hasSize(1);
        assertThat(inference.evidence(id)).hasSize(2);
        assertThat(track.getConfirmations()).isEqualTo(1);
        var moved=trackObservation("A","103.806",now.minusSeconds(10));
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(List.of(moved),0,200,1,1));
        inference.refresh();
        assertThat(inference.current("190").getFirst().getId()).isEqualTo(id);
        assertThat(inference.current("190").getFirst().getStatus()).isEqualTo("ACTIVE");
        mvc.perform(get("/api/v1/map/inferred-tracks?serviceId=190"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].identityStatus").value("INFERRED"))
                .andExpect(jsonPath("$.items[0].directiveEligible").value(false))
                .andExpect(jsonPath("$.items[0].observationIds.length()").value(3));
        // Two candidates claiming one track must not inherit its identity after an overtake/split.
        var close1=trackObservation("A","103.8061",now.minusSeconds(1));
        var close2=trackObservation("A","103.8062",now.minusSeconds(1));
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(List.of(close1,close2),0,200,2,1));
        inference.refresh();
        assertThat(inference.current("190").getFirst().getStatus()).isEqualTo("AMBIGUOUS");
        assertThat(inference.current("190")).hasSize(2);
        assertThat(inference.current("190")).extracting(com.csd.occ.operations.domain.InferredVehicleTrack::getId).doesNotContain(id);
        assertThat(inference.evidence(id)).hasSize(3);
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(List.of(),0,200,0,0));
        jdbc.update("update operations.inferred_vehicle_track set last_seen_at = ?",java.sql.Timestamp.from(now.minusSeconds(301)));
        inference.refresh();
        assertThat(inference.current("190")).isEmpty();
        assertThat(jdbc.queryForObject("select status from operations.inferred_vehicle_track where id = ?",String.class,id)).isEqualTo("EXPIRED");
    }

    private LiveMapPort.Observation trackObservation(String stop,String lon,java.time.Instant time) {
        return new LiveMapPort.Observation(java.util.UUID.randomUUID(),stop,"190",1,1,time.plusSeconds(300),
                new BigDecimal("1.35"),new BigDecimal(lon),time,false);
    }

    @Test void sharedStopEtasKeepLeaderAndFollowerIdsWhenAnchorStopsChange() {
        jdbc.update("delete from operations.inferred_observation_link");
        jdbc.update("delete from operations.inferred_vehicle_track");
        jdbc.update("delete from operations.inferred_track_frame");
        when(routeReference.route("190")).thenReturn(Optional.of(new RouteReferencePort.Route("190", List.of(
                new RouteReferencePort.Direction("SMRT",1,List.of(
                        new RouteReferencePort.Stop("A",1,new BigDecimal("1.35"),new BigDecimal("103.80")),
                        new RouteReferencePort.Stop("B",2,new BigDecimal("1.35"),new BigDecimal("103.82"))))))));
        var now=java.time.Instant.now();
        var initial=List.of(etaObservation("A","103.805",now.minusSeconds(70),now.plusSeconds(120)),
                etaObservation("B","103.8051",now.minusSeconds(69),now.plusSeconds(160)),
                etaObservation("A","103.803",now.minusSeconds(70),now.plusSeconds(600)),
                etaObservation("B","103.8031",now.minusSeconds(69),now.plusSeconds(640)));
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(initial,0,200,4,1));
        inference.refresh();
        var first=inference.current("190");
        assertThat(first).hasSize(2);
        var leader=first.stream().filter(t -> t.getLongitude()>103.804).findFirst().orElseThrow().getId();
        var follower=first.stream().filter(t -> t.getLongitude()<103.804).findFirst().orElseThrow().getId();
        var moved=List.of(etaObservation("A","103.810",now.minusSeconds(10),now.plusSeconds(135)),
                etaObservation("A","103.805",now.minusSeconds(10),now.plusSeconds(620)));
        when(liveMap.observations(null,0,200)).thenReturn(new LiveMapPort.Page(moved,0,200,2,1));
        inference.refresh();
        var updated=inference.current("190");
        assertThat(updated).hasSize(2).allMatch(t -> t.getStatus().equals("ACTIVE"));
        assertThat(updated.stream().filter(t -> t.getId().equals(leader)).findFirst().orElseThrow().getLongitude()).isEqualTo(103.810);
        assertThat(updated.stream().filter(t -> t.getId().equals(follower)).findFirst().orElseThrow().getLongitude()).isEqualTo(103.805);
        inference.refresh();
        assertThat(inference.current("190")).hasSize(2).allMatch(t -> t.getConfirmations()==2);
    }

    private LiveMapPort.Observation etaObservation(String stop,String lon,java.time.Instant source,java.time.Instant eta) {
        return new LiveMapPort.Observation(java.util.UUID.randomUUID(),stop,"190",1,1,eta,
                new BigDecimal("1.35"),new BigDecimal(lon),source,false);
    }

    @BeforeEach
    void routeIsAvailableWithoutLiveBusArrivals() {
        when(routeReference.route("190")).thenReturn(Optional.of(new RouteReferencePort.Route("190",
                List.of(new RouteReferencePort.Direction("SMRT", 1, List.of(
                        new RouteReferencePort.Stop("44251", 8, new BigDecimal("1.3786545"),
                                new BigDecimal("103.7584354"))))))));
    }

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
        mvc.perform(get("/api/v1/demo-runs/{runId}/route", runId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/demo-runs/{runId}/route", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo-runs/{runId}/route", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeKind").value("STOP_TO_STOP_SCHEMATIC"))
                .andExpect(jsonPath("$.classification").value("SIMULATION"))
                .andExpect(jsonPath("$.sequences[0].operator").value("SMRT"))
                .andExpect(jsonPath("$.sequences[0].stops[0].stopCode").value("44251"));
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
        mvc.perform(post("/api/v1/demo-runs/{runId}/vehicles", runId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placement.replace("1.3786545", "1.2800000")))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from operations.demo_assignment", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select created_by_sub from operations.demo_run where id = ?::uuid",
                String.class, runId)).isEqualTo("controller-1");
    }

    @Test
    void unknownDirectionCannotCreateADemoRun() throws Exception {
        mvc.perform(post("/api/v1/demo-runs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"190\",\"directionId\":9}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openApiDescribesTheProtectedDemoFleetEndpoints() throws Exception {
        when(liveMap.observations("190", 0, 50)).thenReturn(new LiveMapPort.Page(List.of(), 0, 50, 0, 0));
        mvc.perform(get("/api/v1/map/observed-buses").param("serviceId", "190"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/map/settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshIntervalMs").value(60000));
        mvc.perform(get("/demo-map.html"))
                .andExpect(status().isOk());
        String document = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(document).contains("/api/v1/demo-runs", "getDemoRunRoute", "placeDemoVehicle", "bearerAuth",
                "DemoVehicleResponse");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "generated-openapi.json"), document);
    }
}
