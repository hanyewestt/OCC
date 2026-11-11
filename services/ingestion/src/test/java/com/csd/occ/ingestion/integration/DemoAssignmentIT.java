package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.DemoReplayService;
import com.csd.occ.ingestion.domain.DemoRunConfiguration;
import com.csd.occ.ingestion.domain.IdentityProviderException;
import com.csd.occ.ingestion.domain.IdentityProviderPort;
import com.csd.occ.ingestion.domain.OccRole;
import com.csd.occ.ingestion.domain.PendingAssignmentOperationsPort;
import com.csd.occ.ingestion.infrastructure.config.BusArrivalSchedule;
import com.csd.occ.ingestion.infrastructure.config.DemoReplaySchedule;
import com.csd.occ.ingestion.infrastructure.config.HazardSchedule;
import com.csd.occ.ingestion.infrastructure.config.PacingSchedule;
import com.csd.occ.ingestion.infrastructure.config.TrafficIncidentSchedule;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL acceptance tests for the Planning-owned conditional assignment command. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "occ.datamall.account-key=synthetic-key", "occ.ingestion.import-reference-on-startup=false",
        "occ.ingestion.seed-targets-on-startup=false",
        "occ.security.peer.planning-to-ingestion-token=test-planning-to-ingestion-token",
        "occ.security.peer.disruption-to-ingestion-token=test-disruption-to-ingestion-token"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DemoAssignmentIT {
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String PLANNING_TOKEN = "test-planning-to-ingestion-token";
    private static final String DISRUPTION_TOKEN = "test-disruption-to-ingestion-token";
    private static final String INTERNAL_ASSIGNMENT =
            "/internal/v1/ingestion/demo-runs/{runId}/buses/{busId}/assignment-operations/{operationId}";
    private static final String PUBLIC_ASSIGNMENT = "/api/v1/admin/demo-runs/{runId}/buses/{busId}/assignment";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/ingestion-test-schema.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_ingestion");
        registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }

    @MockitoBean IdentityProviderPort identityProvider;
    @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule;
    @MockitoBean TrafficIncidentSchedule trafficSchedule;
    @MockitoBean DemoReplaySchedule replaySchedule;
    @MockitoBean PacingSchedule pacingSchedule;
    @MockitoBean HazardSchedule hazardSchedule;
    @MockitoBean BunchingControlPort bunching;
    @MockitoBean PendingAssignmentOperationsPort pendingAssignments;
    @MockitoBean Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DemoReplayService replay;

    @BeforeEach
    void cleanFixture() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("delete from ingestion.application_audit_event where run_id in "
                + "(select id from ingestion.demo_run where scenario_key='task6-assignment-it')");
        jdbc.update("delete from ingestion.demo_vehicle_assignment where demo_run_id in "
                + "(select id from ingestion.demo_run where scenario_key='task6-assignment-it')");
        jdbc.update("delete from ingestion.demo_run where scenario_key='task6-assignment-it'");
    }

    @Test
    void planningPeerCanMutateIdempotentlyButUserTokensAndPublicIngestionRoutesCannot() throws Exception {
        UUID runId = run("RUNNING");
        UUID captainId = UUID.randomUUID();
        String busId = bus(runId, null);
        eligibleCaptain(captainId);
        UUID operationId = UUID.randomUUID();
        String payload = command(captainId, 0);

        mvc.perform(get("/internal/v1/ingestion/demo-fleet").param("demoRunId", runId.toString())
                        .header("X-OCC-Peer-Token", PLANNING_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].assignmentRevision").value(0));
        MvcResult first = putInternal(runId, busId, operationId, payload, PLANNING_TOKEN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.afterVersion").value(1)).andReturn();
        assertThat(jdbc.queryForObject("select captain_user_id from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", UUID.class, runId, busId)).isEqualTo(captainId);
        assertThat(jdbc.queryForObject("select assignment_revision from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", Integer.class, runId, busId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event "
                + "where run_id=? and entity_id=? and category='ACCOUNT_ASSIGNMENT'", Integer.class, runId, busId))
                .isEqualTo(1);
        mvc.perform(get("/internal/v1/ingestion/demo-fleet").param("demoRunId", runId.toString())
                        .header("X-OCC-Peer-Token", PLANNING_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].assignmentRevision").value(1));

        MvcResult replay = putInternal(runId, busId, operationId, payload, PLANNING_TOKEN)
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        putInternal(runId, busId, UUID.randomUUID(), command(null, 0), PLANNING_TOKEN)
                .andExpect(status().isConflict());

        mvc.perform(put(INTERNAL_ASSIGNMENT, runId, busId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("X-OCC-Peer-Token", DISRUPTION_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(put(INTERNAL_ASSIGNMENT, runId, busId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .with(actor("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(put(INTERNAL_ASSIGNMENT, runId, busId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        for (String role : new String[] {"ADMIN", "CONTROLLER", "CAPTAIN"}) {
            mvc.perform(put(PUBLIC_ASSIGNMENT, runId, busId)
                            .contentType(MediaType.APPLICATION_JSON).content(payload).with(actor(role)))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void adminAssignmentProjectionMergesPlanningPendingOperationAndFailsClosedWhenPlanningIsUnavailable() throws Exception {
        UUID runId = run("RUNNING");
        String busId = bus(runId, null);
        UUID operationId = UUID.randomUUID();
        when(pendingAssignments.findPending(runId)).thenReturn(java.util.List.of(
                new PendingAssignmentOperationsPort.Pending(busId, operationId, "PENDING")));

        mvc.perform(get("/api/v1/admin/demo-runs/active/assignments").with(actor("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.items[0].busId").value(busId))
                .andExpect(jsonPath("$.items[0].assignmentRevision").value(0))
                .andExpect(jsonPath("$.items[0].pendingOperation.operationId").value(operationId.toString()));
        mvc.perform(get("/api/v1/admin/demo-runs/active/assignments").with(actor("CONTROLLER")))
                .andExpect(status().isForbidden());

        when(pendingAssignments.findPending(runId)).thenThrow(new RuntimeException("synthetic peer outage"));
        mvc.perform(get("/api/v1/admin/demo-runs/active/assignments").with(actor("ADMIN")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void expectedRevisionIsCheckedForNoOpsAndNoOpsDoNotAdvanceIt() throws Exception {
        UUID runId = run("RUNNING");
        String busId = bus(runId, null);

        UUID operationId = UUID.randomUUID();
        MvcResult noOp = putInternal(runId, busId, operationId, command(null, 0), PLANNING_TOKEN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.afterVersion").value(0)).andReturn();
        putInternal(runId, busId, UUID.randomUUID(), command(null, 1), PLANNING_TOKEN)
                .andExpect(status().isConflict());
        MvcResult replay = putInternal(runId, busId, operationId, command(null, 0), PLANNING_TOKEN)
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(noOp.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("select assignment_revision from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", Integer.class, runId, busId)).isZero();
    }

    @Test
    void exactReceiptReplaysBeforeRunEligibilityAndCleanupWhileChangedPayloadConflicts() throws Exception {
        UUID runId = run("RUNNING");
        UUID captainId = UUID.randomUUID();
        String busId = bus(runId, null);
        eligibleCaptain(captainId);
        UUID operationId = UUID.randomUUID();
        String payload = command(captainId, 0);

        MvcResult original = putInternal(runId, busId, operationId, payload, PLANNING_TOKEN)
                .andExpect(status().isOk()).andReturn();
        jdbc.update("update ingestion.demo_run set status='CLOSED', closed_at=? where id=?",
                java.sql.Timestamp.from(NOW), runId);
        jdbc.update("delete from ingestion.demo_vehicle_assignment where demo_run_id=?", runId);
        jdbc.update("delete from ingestion.demo_run where id=?", runId);
        clearInvocations(identityProvider);

        MvcResult replay = putInternal(runId, busId, operationId, payload, PLANNING_TOKEN)
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        putInternal(runId, busId, operationId, command(null, 0), PLANNING_TOKEN).andExpect(status().isConflict());
        verify(identityProvider, never()).get(captainId);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_assignment_receipt where operation_id=?",
                Integer.class, operationId)).isEqualTo(1);
    }

    @Test
    void requiresEligibleCaptainAndRejectsCaptainsAlreadyAssignedWithinTheRun() throws Exception {
        UUID runId = run("RUNNING");
        UUID ownedCaptain = UUID.randomUUID();
        String targetBus = bus(runId, null);
        bus(runId, ownedCaptain);
        eligibleCaptain(ownedCaptain);

        putInternal(runId, targetBus, UUID.randomUUID(), command(ownedCaptain, 0), PLANNING_TOKEN)
                .andExpect(status().isConflict());
        UUID missingCaptain = UUID.randomUUID();
        when(identityProvider.get(missingCaptain)).thenReturn(Optional.empty());
        putInternal(runId, targetBus, UUID.randomUUID(), command(missingCaptain, 0), PLANNING_TOKEN)
                .andExpect(status().isConflict());
        UUID controllerId = UUID.randomUUID();
        when(identityProvider.get(controllerId)).thenReturn(Optional.of(providerUser(controllerId, OccRole.CONTROLLER, true)));
        putInternal(runId, targetBus, UUID.randomUUID(), command(controllerId, 0), PLANNING_TOKEN)
                .andExpect(status().isConflict());
        UUID unavailableId = UUID.randomUUID();
        UUID recoveryOperation = UUID.randomUUID();
        when(identityProvider.get(unavailableId)).thenThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE));
        putInternal(runId, targetBus, recoveryOperation, command(unavailableId, 0), PLANNING_TOKEN)
                .andExpect(status().isServiceUnavailable());
        eligibleCaptain(unavailableId);
        putInternal(runId, targetBus, recoveryOperation, command(unavailableId, 0), PLANNING_TOKEN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(jdbc.queryForObject("select captain_user_id from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", UUID.class, runId, targetBus)).isEqualTo(unavailableId);
        assertThat(jdbc.queryForObject("select assignment_revision from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", Integer.class, runId, targetBus)).isEqualTo(1);
    }

    @Test
    void initialConfiguredCaptainIdentitiesAreRevalidatedBeforeRunCreation() {
        UUID controllerId = UUID.randomUUID();
        when(identityProvider.get(controllerId)).thenReturn(Optional.of(providerUser(controllerId, OccRole.CONTROLLER, true)));
        var defaults = DemoRunConfiguration.defaults();
        var request = new DemoRunConfiguration(defaults.serviceId(), defaults.directionId(), defaults.busCount(),
                defaults.dispatchGap(), defaults.busGaps(), defaults.monitoredStopCode(), defaults.scenario(),
                java.util.List.of(controllerId, UUID.randomUUID(), UUID.randomUUID()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> replay.start(request))
                .isInstanceOf(RuntimeException.class);
        verify(identityProvider).get(controllerId);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_run", Integer.class)).isZero();
    }

    @Test
    void assignmentReceiptAndAuditRollBackTogetherWhenAuditInsertFails() throws Exception {
        UUID runId = run("RUNNING");
        UUID captainId = UUID.randomUUID();
        String busId = bus(runId, null);
        eligibleCaptain(captainId);
        UUID operationId = UUID.randomUUID();
        jdbc.execute("""
                CREATE FUNCTION ingestion.reject_assignment_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic audit write failure'; END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER reject_assignment_test_audit BEFORE INSERT ON ingestion.application_audit_event "
                + "FOR EACH ROW EXECUTE FUNCTION ingestion.reject_assignment_test_audit()");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    putInternal(runId, busId, operationId, command(captainId, 0), PLANNING_TOKEN)
                            .andExpect(status().is5xxServerError()))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_assignment_test_audit ON ingestion.application_audit_event");
            jdbc.execute("DROP FUNCTION IF EXISTS ingestion.reject_assignment_test_audit()");
        }
        assertThat(jdbc.queryForObject("select captain_user_id from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", UUID.class, runId, busId)).isNull();
        assertThat(jdbc.queryForObject("select assignment_revision from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", Integer.class, runId, busId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_assignment_receipt where operation_id=?",
                Integer.class, operationId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where run_id=?",
                Integer.class, runId)).isZero();
    }

    @Test
    void runLockSerializesTwoBusesCompetingForOneCaptain() throws Exception {
        UUID runId = run("RUNNING");
        String firstBus = bus(runId, null);
        String secondBus = bus(runId, null);
        UUID captainId = UUID.randomUUID();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            providerEntered.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test provider barrier timed out");
            return Optional.of(providerUser(captainId, OccRole.CAPTAIN, true));
        }).when(identityProvider).get(captainId);

        ExecutorService requests = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        try {
            CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> requestAfterBarrier(
                    ready, startTogether, runId, firstBus, firstOperation, captainId), requests);
            CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> requestAfterBarrier(
                    ready, startTogether, runId, secondBus, secondOperation, captainId), requests);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startTogether.countDown();
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean competingRequestWaited = false;
            while (System.nanoTime() < deadline && !competingRequestWaited) {
                competingRequestWaited = jdbc.queryForObject("""
                        select count(*) > 0 from pg_stat_activity
                         where datname=current_database() and wait_event_type='Lock'
                           and query ilike '%demo_run%'
                        """, Boolean.class);
                if (!competingRequestWaited) Thread.sleep(25);
            }
            assertThat(competingRequestWaited).as("the second request waits on the shared run lock").isTrue();
            releaseProvider.countDown();
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus()).sorted().toList())
                    .containsExactly(200, 409);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow()
                    .getResponse().getContentAsString()).contains("\"outcome\":\"APPLIED\"");
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow()
                    .getResponse().getContentAsString()).contains("\"outcome\":\"REJECTED\"");
        } finally {
            releaseProvider.countDown();
            requests.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and captain_user_id=?", Integer.class, runId, captainId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(assignment_revision) from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=?", Long.class, runId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_assignment_receipt "
                + "where demo_run_id=? and outcome='APPLIED'", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_assignment_receipt "
                + "where demo_run_id=? and outcome='REJECTED'", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void runClosureSerializesAgainstAssignmentAndClosedRunRejectsTheCommand() throws Exception {
        UUID runId = run("RUNNING");
        UUID captainId = UUID.randomUUID();
        String busId = bus(runId, null);
        eligibleCaptain(captainId);
        UUID operationId = UUID.randomUUID();
        ExecutorService requests = Executors.newSingleThreadExecutor();
        CountDownLatch requestStarted = new CountDownLatch(1);
        try (Connection closure = dataSource.getConnection()) {
            closure.setAutoCommit(false);
            try (PreparedStatement lock = closure.prepareStatement(
                    "select id from ingestion.demo_run where id=? for update")) {
                lock.setObject(1, runId);
                try (ResultSet row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            CompletableFuture<MvcResult> attempted = CompletableFuture.supplyAsync(() -> {
                requestStarted.countDown();
                try {
                    return putInternal(runId, busId, operationId, command(captainId, 0), PLANNING_TOKEN).andReturn();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }, requests);
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean databaseWaitObserved = false;
            while (System.nanoTime() < deadline && !databaseWaitObserved) {
                databaseWaitObserved = jdbc.queryForObject("""
                        select count(*) > 0 from pg_stat_activity
                         where datname=current_database() and wait_event_type='Lock'
                           and query ilike '%demo_run%'
                        """, Boolean.class);
                if (!databaseWaitObserved) Thread.sleep(25);
            }
            assertThat(databaseWaitObserved).as("assignment request waits for the locked run row").isTrue();
            try (PreparedStatement close = closure.prepareStatement(
                    "update ingestion.demo_run set status='CLOSED', closed_at=? where id=?")) {
                close.setObject(1, java.sql.Timestamp.from(NOW));
                close.setObject(2, runId);
                assertThat(close.executeUpdate()).isEqualTo(1);
            }
            closure.commit();
            assertThat(attempted.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        } finally {
            requests.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select captain_user_id from ingestion.demo_vehicle_assignment "
                + "where demo_run_id=? and bus_id=?", UUID.class, runId, busId)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.demo_assignment_receipt where operation_id=?",
                Integer.class, operationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor_subject from ingestion.application_audit_event "
                + "where run_id=? and entity_id=? and event_type='BUS_ASSIGNMENT_REJECTED'", String.class, runId, busId))
                .isEqualTo("verified-admin");
    }

    private UUID run(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ingestion.demo_run (id, scenario_key, scenario_version, seed, status, started_at) "
                + "values (?, 'task6-assignment-it', '1', 1, ?, ?)", id, status, java.sql.Timestamp.from(NOW));
        return id;
    }

    private String bus(UUID runId, UUID captainId) {
        String busId = "task6-bus-" + UUID.randomUUID();
        jdbc.update("""
                insert into ingestion.demo_vehicle_assignment
                    (demo_run_id, trip_id, service_id, direction_id, operator, vehicle_registration_number,
                     vehicle_type, captain_code, bus_id, captain_user_id)
                values (?, ?, '190', 1, 'SBST', ?, 'DD', ?, ?, ?)
                """, runId, busId, "R" + UUID.randomUUID().toString().substring(0, 8),
                "C" + UUID.randomUUID().toString().substring(0, 8), busId, captainId);
        return busId;
    }

    private void eligibleCaptain(UUID id) {
        doReturn(Optional.of(providerUser(id, OccRole.CAPTAIN, true))).when(identityProvider).get(id);
    }

    private IdentityProviderPort.User providerUser(UUID id, OccRole role, boolean available) {
        return new IdentityProviderPort.User(id, "Synthetic Captain", "captain@example.test", role, available,
                NOW.minusSeconds(1), null, null);
    }

    private org.springframework.test.web.servlet.ResultActions putInternal(UUID runId, String busId,
            UUID operationId, String body, String token) throws Exception {
        return mvc.perform(put(INTERNAL_ASSIGNMENT, runId, busId, operationId)
                .header("X-OCC-Peer-Token", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult requestAfterBarrier(CountDownLatch ready, CountDownLatch startTogether,
            UUID runId, String busId, UUID operationId, UUID captainId) {
        ready.countDown();
        try {
            if (!startTogether.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("request barrier timed out");
            return putInternal(runId, busId, operationId, command(captainId, 0), PLANNING_TOKEN).andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String command(UUID captainId, int expectedVersion) {
        return "{\"captainUserId\":" + (captainId == null ? "null" : "\"" + captainId + "\"")
                + ",\"expectedVersion\":" + expectedVersion + ",\"actorSubject\":\"verified-admin\"}";
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor actor(String role) {
        return jwt().jwt(token -> token.subject("verified-" + role.toLowerCase()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
