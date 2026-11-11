package com.csd.occ.planning.advisory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.advisory.domain.*;
import com.csd.occ.planning.advisory.infrastructure.config.AssignmentRecoveryProperties;
import com.csd.occ.planning.advisory.infrastructure.config.AssignmentOperationRecoverySchedule;
import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import java.sql.Timestamp;
import java.math.BigDecimal;
import com.csd.occ.planning.recommendation.domain.*;
import com.csd.occ.planning.diagnosis.domain.PlanningScopeService;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "OCC_PLANNING_DB_USERNAME=occ_planning", "OCC_PLANNING_DB_PASSWORD=synthetic-planning",
        "occ.security.peer.ingestion-to-planning-token=synthetic-ingestion-peer-token",
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1", "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED=false", "OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false",
        "OCC_PLANNING_ASSIGNMENT_RECOVERY_INTERVAL=PT1H", "OCC_PLANNING_ASSIGNMENT_RECOVERY_BATCH_SIZE=25",
        "OCC_PLANNING_GEMINI_API_KEY=test-no-network", "SUPABASE_PROJECT_URL=https://issuer.test",
        "SUPABASE_JWKS_URL=https://issuer.test/auth/v1/jwks"
})
@AutoConfigureMockMvc
@Import(AdvisoryPersistenceIT.Time.class)
@Testcontainers(disabledWithoutDocker = true)
class AdvisoryPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final PlanningActor CONTROLLER = new PlanningActor("controller", PlanningActor.Role.CONTROLLER);
    private PlanningActor captainActor() { return new PlanningActor(captain.toString(), PlanningActor.Role.CAPTAIN); }
    private static final String MESSAGE = "Service 190 may be delayed near School.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger simulatedRemoteMutations = new AtomicInteger();
    private static final AtomicInteger simulatedRemoteRequests = new AtomicInteger();
    private static final Map<UUID, String> simulatedRemoteReceipts = new ConcurrentHashMap<>();
    private static volatile String assignmentTransportMode = "UNAVAILABLE";
    private static volatile HttpServer assignmentServer;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/create-planning-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
        registry.add("occ.diagnosis.ingestion-internal-url", AdvisoryPersistenceIT::assignmentServerUrl);
    }

    private static String assignmentServerUrl() {
        try {
            if (assignmentServer == null) {
                synchronized (AdvisoryPersistenceIT.class) {
                    if (assignmentServer == null) {
                        assignmentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        assignmentServer.createContext("/", AdvisoryPersistenceIT::handleAssignmentRequest);
                        assignmentServer.start();
                    }
                }
            }
            return "http://127.0.0.1:" + assignmentServer.getAddress().getPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not start local assignment test endpoint", exception);
        }
    }

    @AfterAll
    static void stopAssignmentServer() {
        if (assignmentServer != null) assignmentServer.stop(0);
    }

    private static void handleAssignmentRequest(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        int attempt = simulatedRemoteRequests.incrementAndGet();
        if ("UNAVAILABLE".equals(assignmentTransportMode)) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        var segments = exchange.getRequestURI().getPath().split("/");
        var runId = UUID.fromString(segments[5]);
        var busId = segments[7];
        var operationId = UUID.fromString(segments[9]);
        var body = JSON.readTree(exchange.getRequestBody());
        var captainId = body.path("captainUserId").isNull() ? null : body.path("captainUserId").asText();
        var receiptJson = simulatedRemoteReceipts.computeIfAbsent(operationId, ignored -> {
            boolean captainConflict = "CAPTAIN_ALREADY_ASSIGNED".equals(assignmentTransportMode);
            if (!captainConflict) simulatedRemoteMutations.incrementAndGet();
            var receipt = JSON.createObjectNode().put("operationId", operationId.toString()).put("runId", runId.toString())
                    .put("busId", busId).put("outcome", captainConflict ? "REJECTED" : "APPLIED")
                    .put("beforeVersion", 0).put("afterVersion", captainConflict ? 0 : 1);
            receipt.putNull("previousCaptainUserId");
            receipt.put("captainUserId", captainId);
            if ("MISMATCHED_RECEIPT".equals(assignmentTransportMode)) receipt.put("captainUserId", UUID.randomUUID().toString());
            if (captainConflict) receipt.put("reason", "CAPTAIN_ALREADY_ASSIGNED");
            return JSON.writeValueAsString(receipt);
        });
        if (attempt == 1 && "DROP_FIRST_RESPONSE".equals(assignmentTransportMode)) {
            exchange.close();
            return;
        }
        var bytes = receiptJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired RecommendationRecordPort records;
    @MockitoBean PlanningScopeService recommendationScopes;
    @Autowired InstructionService instructions;
    @Autowired AdvisoryGroupService advisoryGroups;
    @Autowired BunchingInstructionService bunchingInstructions;
    @Autowired BunchingRecommendationRefreshService refreshes;
    @Autowired PublicNoticeService notices;
    @Autowired AdvisoryReconciliationService reconciliation;
    @Autowired InstructionRunReconciliationService instructionRuns;
    @Autowired AdvisoryRetentionService retention;
    @Autowired AdvisoryProposalRepository proposals;
    @Autowired AssignmentOperationRepository assignmentOperations;
    @Autowired AssignmentBusGuardRepository assignmentGuards;
    @Autowired AssignmentBusGuards assignmentBusGuards;
    @Autowired AssignmentOperationFinalizer assignmentFinalizer;
    @Autowired AssignmentIngestionPort assignmentIngestion;
    @Autowired com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository applicationAuditEvents;
    @Autowired Clock clock;
    @Autowired AdvisoryModelEvidenceRepository modelEvidence;
    @Autowired IssuedInstructionRepository issued;
    @Autowired PublicNoticeRepository publications;
    @MockitoBean AdvisoryContextPort context;
    @MockitoBean PlanningScopePort scopes;
    @MockitoBean AdvisoryDraftPort drafts;
    private final Map<UUID, AdvisoryContextPort.Hazard> sources = new ConcurrentHashMap<>();
    private UUID run;
    private UUID captain;
    private AdvisoryContextPort.Bus bus;
    private AdvisoryContextPort.Hazard hazard;

    @BeforeEach
    void resetRowsAndExternalContext() {
        jdbc.execute("TRUNCATE planning.application_audit_event");
        jdbc.execute("TRUNCATE planning.bus_assignment_operation, planning.bus_assignment_guard CASCADE");
        jdbc.execute("TRUNCATE planning.issued_instruction, planning.advisory_proposal, planning.advisory_model_evidence, planning.public_notice");
        jdbc.execute("TRUNCATE planning.recommendation_decision, planning.model_invocation_record, planning.recommendation");
        sources.clear();
        assignmentTransportMode = "UNAVAILABLE";
        simulatedRemoteMutations.set(0);
        simulatedRemoteRequests.set(0);
        simulatedRemoteReceipts.clear();
        when(recommendationScopes.includesDiagnosis(any())).thenReturn(true);
        run = UUID.randomUUID(); captain = UUID.randomUUID();
        bus = new AdvisoryContextPort.Bus("demo-190-b", "demo-190-b", "SBS5898U", "190", 1, captain, "ASSIGNED",
                new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(5), List.of(300),
                        NOW.plusSeconds(60), NOW.plusSeconds(120)));
        hazard = source(true);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", run));
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus), 1));
        when(context.hazard(any())).thenAnswer(invocation -> sources.get(invocation.getArgument(0)));
        when(context.limits()).thenReturn(new AdvisoryContextPort.Limits(10, 900, 10, Duration.ofMinutes(5), Duration.ofHours(12)));
    }

    @Test
    void adminAssignmentCommandRequiresAdminIdempotencyAndExplicitNullableFields() throws Exception {
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        String key = UUID.randomUUID().toString();
        mvc.perform(put(path).contentType("application/json").content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pendingAssignmentReplayIsStableAndChangedExplicitNullPayloadConflicts() throws Exception {
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        String key = UUID.randomUUID().toString();
        var admin = jwt().jwt(token -> token.subject("admin-a")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var first = mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        var operationId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(first).path("operationId").asText();
        mvc.perform(get("/api/v1/planning/admin/assignment-operations/" + operationId).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/internal/v1/planning/demo-runs/" + run + "/pending-assignment-operations")
                        .header("X-OCC-Peer-Token", "synthetic-ingestion-peer-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].busId").value(bus.busId()))
                .andExpect(jsonPath("$.items[0].operationId").value(operationId))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operationId").value(operationId));
        mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":\"" + captain + "\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());

        var otherBus = groupedFleet().get(1);
        var otherActor = jwt().jwt(token -> token.subject("admin-b"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(put("/api/v1/planning/admin/demo-runs/" + run + "/buses/" + otherBus.busId() + "/assignment")
                        .with(otherActor).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.operationId").value(org.hamcrest.Matchers.not(operationId)));
    }

    @Test
    void recoveredWorkerReplaysCommittedRemoteOperationAfterLostResponseExactlyOnce() throws Exception {
        assignmentTransportMode = "DROP_FIRST_RESPONSE";
        var admin = jwt().jwt(token -> token.subject("recovery-admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var key = UUID.randomUUID().toString();
        var path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        var response = mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":\"" + captain + "\",\"expectedVersion\":0}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        UUID operationId = UUID.fromString(JSON.readTree(response).path("operationId").asText());
        var operation = assignmentOperations.findById(operationId).orElseThrow();
        assertThat(operation.status()).isEqualTo("PENDING");
        assertThat(assignmentGuards.findById(new AssignmentBusGuardId(run, bus.busId())).orElseThrow().pendingOperationId())
                .isEqualTo(operationId);
        assertThat(simulatedRemoteMutations).hasValue(1);
        assertThat(simulatedRemoteReceipts).hasSize(1).containsKey(operationId);
        assertThat(simulatedRemoteRequests).hasValue(1);

        var restartedCoordinator = new AssignmentOperationCoordinator(assignmentOperations, assignmentBusGuards,
                context, instructions, assignmentIngestion, assignmentFinalizer, applicationAuditEvents, clock);
        new AssignmentOperationRecoverySchedule(restartedCoordinator,
                new AssignmentRecoveryProperties(Duration.ofSeconds(5), 10)).recover();

        var completed = assignmentOperations.findById(operationId).orElseThrow();
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.result()).containsEntry("afterVersion", 1);
        assertThat(assignmentGuards.findById(new AssignmentBusGuardId(run, bus.busId())).orElseThrow().pendingOperationId()).isNull();
        assertThat(simulatedRemoteMutations).hasValue(1);
        assertThat(simulatedRemoteRequests).hasValue(2);
        assertThat(applicationAuditEvents.findAll().stream()
                .filter(event -> event.sourceKey().equals("assignment-operation:" + operationId + ":SUCCEEDED")))
                .hasSize(1);
    }

    @Test
    void mismatchedRemoteReceiptCannotClearTheAssignmentGuard() throws Exception {
        assignmentTransportMode = "MISMATCHED_RECEIPT";
        var admin = jwt().jwt(token -> token.subject("mismatch-admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var key = UUID.randomUUID().toString();
        var path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        var response = mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":\"" + captain + "\",\"expectedVersion\":0}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID operationId = UUID.fromString(JSON.readTree(response).path("operationId").asText());
        assertThat(assignmentOperations.findById(operationId).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(assignmentGuards.findById(new AssignmentBusGuardId(run, bus.busId())).orElseThrow().pendingOperationId())
                .isEqualTo(operationId);
        assertThat(applicationAuditEvents.findAll().stream()
                .filter(event -> event.sourceKey().equals("assignment-operation:" + operationId + ":SUCCEEDED")))
                .isEmpty();
    }

    @Test
    void secondBusCannotTakeCaptainAlreadyAssignedElsewhere() throws Exception {
        assignmentTransportMode = "CAPTAIN_ALREADY_ASSIGNED";
        var otherBus = new AdvisoryContextPort.Bus("demo-190-a", "demo-190-a", "SBS1234X", "190", 1,
                null, "UNASSIGNED", bus.progress(), 0);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus, otherBus), 2));
        var admin = jwt().jwt(token -> token.subject("cross-bus-admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var key = UUID.randomUUID().toString();
        var path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + otherBus.busId() + "/assignment";
        var response = mvc.perform(put(path).with(admin).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"captainUserId\":\"" + captain + "\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("CAPTAIN_ALREADY_ASSIGNED");
        var operation = assignmentOperations.findByActorSubjectAndIdempotencyKey("cross-bus-admin", key).orElseThrow();
        UUID operationId = operation.id();
        assertThat(operation.status()).isEqualTo("REJECTED");
        assertThat(operation.result()).containsEntry("outcome", "REJECTED");
        assertThat(assignmentGuards.findById(new AssignmentBusGuardId(run, otherBus.busId())).orElseThrow().pendingOperationId()).isNull();
        assertThat(simulatedRemoteMutations).hasValue(0);
        assertThat(simulatedRemoteReceipts).hasSize(1).containsKey(operationId);
    }

    @Test
    void adminAssignmentIsBlockedByCurrentInstructionAndReturnsItsIdentityAndStatus() throws Exception {
        var outstanding = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-a"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString(outstanding.id().toString())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ISSUED")));
        assertThat(issued.findById(outstanding.id()).orElseThrow().status()).isEqualTo("ISSUED");
    }

    @Test
    void staleAssignmentRevisionIsRejectedWithoutCreatingAnInstructionOrReservation() throws Exception {
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-stale"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"captainUserId\":null,\"expectedVersion\":99}"))
                .andExpect(status().isConflict());
        assertThat(issued.count()).isZero();
    }

    @Test
    void pendingAssignmentProjectionIsRestrictedToTheIngestionPeer() throws Exception {
        String path = "/internal/v1/planning/demo-runs/" + run + "/pending-assignment-operations";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-OCC-Peer-Token", "synthetic-ingestion-peer-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void twoAdminsRacingForOneBusCreateAtMostOnePendingReservation() throws Exception {
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = List.of("admin-a", "admin-b").stream().map(subject -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(put(path).with(jwt().jwt(token -> token.subject(subject))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                        .andReturn().getResponse();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = responses.stream().map(future -> {
                try { return future.get(20, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertThat(results).extracting(response -> response.getStatus()).containsExactlyInAnyOrder(202, 409);
            var accepted = results.stream().filter(response -> response.getStatus() == 202).findFirst().orElseThrow();
            var operationId = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(accepted.getContentAsString()).path("operationId").asText();
            assertThat(operationId).isNotBlank();
            mvc.perform(get("/api/v1/planning/admin/assignment-operations/" + operationId)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        }
    }

    @Test
    void instructionCancellationRacingAssignmentEitherBlocksOrReleasesTheGuard() throws Exception {
        var outstanding = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancel = executor.submit(() -> {
                ready.countDown(); start.await(); return instructions.cancel(outstanding.id(), CONTROLLER);
            });
            var assign = executor.submit(() -> {
                ready.countDown(); start.await();
                return mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                        .andReturn().getResponse();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(cancel.get(20, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
            int assignmentStatus = assign.get(20, TimeUnit.SECONDS).getStatus();
            assertThat(assignmentStatus).isIn(202, 409);
            if (assignmentStatus == 409)
                assertThat(issued.findById(outstanding.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void individualAdvisoryIssuanceRacingAssignmentHasOneGuardWinner() throws Exception {
        var proposal = proposal(InstructionType.REDUCE_SPEED);
        var race = raceAssignmentAgainst(() -> instructions.decide(proposal.id(), null, null, CONTROLLER, null), bus.busId());
        assertThat(race.issueSucceeded()).isEqualTo(race.assignmentStatus() == 409);
        assertThat(issued.count()).isEqualTo(race.issueSucceeded() ? 1 : 0);
    }

    @Test
    void completeGroupIssuanceRacingAssignmentHasOneGuardWinner() throws Exception {
        var fleet = groupedFleet();
        var proposalIds = fleet.stream().limit(3)
                .map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var race = raceAssignmentAgainst(() -> advisoryGroups.decide(proposalIds, CONTROLLER), bus.busId());
        assertThat(race.issueSucceeded()).isEqualTo(race.assignmentStatus() == 409);
        assertThat(issued.count()).isEqualTo(race.issueSucceeded() ? 3 : 0);
        if (!race.issueSucceeded()) assertThat(proposals.findAll()).allSatisfy(value -> assertThat(value.status()).isEqualTo("PENDING"));
    }

    @Test
    void bunchingIssueAndReplacementRacingAssignmentHasOneGuardWinner() throws Exception {
        var recommendationId = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var race = raceAssignmentAgainst(() -> bunchingInstructions.approveAndIssue(recommendationId, CONTROLLER, null),
                bus.busId());
        assertThat(race.issueSucceeded()).isEqualTo(race.assignmentStatus() == 409);
        assertThat(issued.count()).isEqualTo(race.issueSucceeded() ? 1 : 0);
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id=?",
                Integer.class, recommendationId)).isEqualTo(race.issueSucceeded() ? 1 : 0);
    }

    @Test
    void acknowledgementRacingAssignmentKeepsCurrentInstructionAsBlocker() throws Exception {
        var instruction = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acknowledge = executor.submit(() -> {
                ready.countDown(); start.await(); return instructions.acknowledge(instruction.id(), captainActor());
            });
            var assign = executor.submit(() -> {
                ready.countDown(); start.await();
                return mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                        .andReturn().getResponse();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(acknowledge.get(20, TimeUnit.SECONDS).status()).isEqualTo("ACKNOWLEDGED");
            var response = assign.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).contains(instruction.id().toString(), "ACKNOWLEDGED");
        }
    }

    @Test
    void closedRunReconciliationRacingAssignmentClosesTheInstructionAndRejectsNewReservation() throws Exception {
        var outstanding = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "CLOSED", List.of(bus), 1));
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + bus.busId() + "/assignment";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var close = executor.submit(() -> { ready.countDown(); start.await(); instructionRuns.reconcileClosedRuns(); return true; });
            var assign = executor.submit(() -> {
                ready.countDown(); start.await();
                return mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-close"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                        .andReturn().getResponse();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(close.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(assign.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(409);
        }
        assertThat(issued.findById(outstanding.id()).orElseThrow().status()).isEqualTo("CANCELLED");
    }

    private AssignmentRace raceAssignmentAgainst(Runnable issue, String busId) throws Exception {
        String path = "/api/v1/planning/admin/demo-runs/" + run + "/buses/" + busId + "/assignment";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var issueFuture = executor.submit(() -> {
                ready.countDown(); start.await();
                try { issue.run(); return true; }
                catch (AdvisoryConflictException | DataIntegrityViolationException conflict) { return false; }
            });
            var assignmentFuture = executor.submit(() -> {
                ready.countDown(); start.await();
                return mvc.perform(put(path).with(jwt().jwt(token -> token.subject("admin-race"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"captainUserId\":null,\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new AssignmentRace(assignmentFuture.get(20, TimeUnit.SECONDS), issueFuture.get(20, TimeUnit.SECONDS));
        }
    }

    private record AssignmentRace(int assignmentStatus, boolean issueSucceeded) { }

    @Test
    void groupsWholeServiceDirectionsBeforePagination() {
        var fleet = groupedFleet();
        fleet.forEach(value -> proposalFor(value, InstructionType.BE_CAUTIOUS));
        var first = advisoryGroups.pending(0, 1);
        assertThat(first.getTotalElements()).isEqualTo(3);
        var all = java.util.stream.IntStream.range(0, 3)
                .mapToObj(page -> advisoryGroups.pending(page, 1).getContent()).flatMap(List::stream).toList();
        assertThat(all).hasSize(3);
        var service190 = all.stream().filter(group -> group.key().serviceId().equals("190")
                && group.key().directionId() == 1).findFirst().orElseThrow();
        assertThat(service190.members()).hasSize(3);
        assertThat(all.stream().mapToInt(group -> group.members().size()).sum()).isEqualTo(5);
        proposalFor(fleet.getFirst(), InstructionType.REDUCE_SPEED);
        assertThat(advisoryGroups.pending(0, 20).getTotalElements()).isEqualTo(4);
        hazard = source(true);
        proposalFor(bus, InstructionType.BE_CAUTIOUS);
        assertThat(advisoryGroups.pending(0, 20).getTotalElements()).isEqualTo(5);
        hazard = new AdvisoryContextPort.Hazard(hazard.disruptionId(), hazard.kind(), hazard.status(), hazard.inactiveReason(),
                hazard.classification(), hazard.demoRunId(), UUID.randomUUID(), hazard.updatedAt(), hazard.facts(),
                hazard.exposures(), hazard.limitations());
        proposalFor(bus, InstructionType.BE_CAUTIOUS);
        assertThat(advisoryGroups.pending(0, 20).getTotalElements()).isEqualTo(6);
    }

    @Test
    void groupSendCreatesIndependentDeliveriesAndIdenticalRetriesReuseThem() {
        var fleet = groupedFleet();
        var ids = fleet.stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var sent = advisoryGroups.decide(ids, CONTROLLER);
        assertThat(sent).hasSize(3);
        assertThat(issued.count()).isEqualTo(3);
        assertThat(advisoryGroups.decide(ids.reversed(), CONTROLLER)).extracting(IssuedInstruction::id)
                .containsExactlyInAnyOrderElementsOf(sent.stream().map(IssuedInstruction::id).toList());
        assertThat(issued.count()).isEqualTo(3);
        var one = sent.getFirst();
        var captainId = fleet.stream().filter(value -> value.busId().equals(one.busId())).findFirst().orElseThrow().captainUserId();
        instructions.acknowledge(one.id(), new PlanningActor(captainId.toString(), PlanningActor.Role.CAPTAIN));
        assertThat(issued.findAll().stream().filter(value -> value.acknowledgedAt() != null).count()).isEqualTo(1);
    }

    @Test
    void completeGroupsLargerThanTheCollectionPageLimitCanBeSent() {
        var fleet = java.util.stream.IntStream.range(0, 101).mapToObj(index ->
                new AdvisoryContextPort.Bus("bus-" + index, "trip-" + index, "BUS-" + index, "190", 1,
                        UUID.randomUUID(), "ASSIGNED", bus.progress())).toList();
        captureFleet(fleet);
        var ids = fleet.stream().map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var groups = advisoryGroups.pending(0, 1);
        assertThat(groups.getTotalElements()).isEqualTo(1);
        assertThat(groups.getContent().getFirst().members()).hasSize(101);
        assertThat(advisoryGroups.decide(ids, CONTROLLER)).hasSize(101);
        assertThat(issued.count()).isEqualTo(101);
    }

    @Test
    void changedRecipientAssignmentRollsBackEveryDeliveryAndDecision() {
        var fleet = groupedFleet();
        var captured = fleet.stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS)).toList();
        var unavailable = captured.stream().max(java.util.Comparator.comparing(AdvisoryProposal::id)).orElseThrow().busId();
        var current = fleet.stream().map(value -> value.busId().equals(unavailable)
                ? new AdvisoryContextPort.Bus(value.busId(), value.tripId(), value.registrationNumber(), value.serviceId(),
                        value.directionId(), null, "UNASSIGNED", value.progress()) : value).toList();
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", current, current.size()));
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().map(AdvisoryProposal::id).toList(), CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.count()).isZero();
        assertThat(proposals.findAll()).allSatisfy(value -> assertThat(value.status()).isEqualTo("PENDING"));
    }

    @Test
    void groupSendRejectsMixedDirectionsIncompleteMembershipAndChangedRoutes() {
        var fleet = groupedFleet();
        var captured = fleet.stream().map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS)).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(List.of(captured.get(0).id(), captured.get(3).id()), CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().limit(2).map(AdvisoryProposal::id).toList(), CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class);
        var changed = fleet.stream().map(value -> value.busId().equals(bus.busId())
                ? new AdvisoryContextPort.Bus(value.busId(), value.tripId(), value.registrationNumber(), "972", 1,
                        value.captainUserId(), value.assignmentStatus(), value.progress()) : value).toList();
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", changed, changed.size()));
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().limit(3).map(AdvisoryProposal::id).toList(), CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("route");
        assertThat(issued.count()).isZero();
    }

    @Test
    void conflictingMovementInstructionPreventsTheWholeGroupSend() {
        groupedFleet();
        var previous = proposalFor(bus, InstructionType.REDUCE_SPEED);
        instructions.decide(previous.id(), null, null, CONTROLLER, null);
        hazard = source(true);
        var captured = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.REDUCE_SPEED)).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().map(AdvisoryProposal::id).toList(), CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("explicitly replaced");
        assertThat(issued.count()).isEqualTo(1);
        assertThat(proposals.findAll().stream().filter(value -> value.disruptionId().equals(hazard.disruptionId())).toList())
                .allSatisfy(value -> assertThat(value.status()).isEqualTo("PENDING"));
    }

    @Test
    void changedHazardEvidencePreventsGroupDelivery() {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        sources.put(hazard.disruptionId(), new AdvisoryContextPort.Hazard(hazard.disruptionId(), hazard.kind(), "ACTIVE", null,
                hazard.classification(), run, UUID.randomUUID(), NOW, hazard.facts(), hazard.exposures(), hazard.limitations()));
        assertThatThrownBy(() -> advisoryGroups.decide(ids, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("evidence changed");
        assertThat(issued.count()).isZero();
    }

    @Test
    void passedHazardExposurePreventsEveryGroupDelivery() {
        var fleet = groupedFleet();
        var ids = fleet.stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var passed = new AdvisoryContextPort.Progress("next-stop", 11, NOW, 30, List.of(5), List.of(300));
        var current = fleet.stream().map(value -> value.busId().equals(bus.busId())
                ? new AdvisoryContextPort.Bus(value.busId(), value.tripId(), value.registrationNumber(), value.serviceId(),
                        value.directionId(), value.captainUserId(), value.assignmentStatus(), passed) : value).toList();
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", current, current.size()));
        assertThatThrownBy(() -> advisoryGroups.decide(ids, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("no current downstream exposure");
        assertThat(issued.count()).isZero();
    }

    @Test
    void groupSendRejectsDuplicateRecipientsAndAnotherSimulation() {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(List.of(ids.getFirst(), ids.getFirst()), CONTROLLER))
                .isInstanceOf(IllegalArgumentException.class);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", UUID.randomUUID()));
        assertThatThrownBy(() -> advisoryGroups.decide(ids, CONTROLLER)).isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.count()).isZero();
    }

    @Test
    void concurrentGroupApprovalsReturnTheSameDeliveries() throws Exception {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<List<UUID>> send = () -> {
                start.await();
                return advisoryGroups.decide(ids, CONTROLLER).stream().map(IssuedInstruction::id).toList();
            };
            var first = workers.submit(send);
            var second = workers.submit(send);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).containsExactlyElementsOf(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(issued.count()).isEqualTo(3);
    }

    @Test
    void groupEndpointsAllowAdminReadsAndControllerDecisionsAndDenyCaptains() throws Exception {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        for (String role : List.of("CONTROLLER", "ADMIN")) {
            mvc.perform(get("/api/v1/planning/advisory-groups").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].members.length()").value(3));
        }
        var body = "{\"proposalIds\":[" + ids.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
        mvc.perform(post("/api/v1/planning/advisory-groups/decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(post("/api/v1/planning/advisory-groups/decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/planning/advisory-groups").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/planning/advisory-groups/decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .contentType("application/json").content("{\"proposalIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupSendDeliversEachRecipientCaptainOnlyTheirOwnInstruction() throws Exception {
        var fleet = groupedFleet();
        var recipients = fleet.subList(0, 3);
        var outsideGroup = fleet.subList(3, 5);
        var ids = recipients.stream().map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var body = "{\"proposalIds\":[" + ids.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
        mvc.perform(post("/api/v1/planning/advisory-groups/decisions").with(controller())
                .contentType("application/json").content(body)).andExpect(status().isOk());

        for (var recipient : recipients) {
            var delivered = issued.findAll().stream().filter(value -> value.busId().equals(recipient.busId())).toList();
            assertThat(delivered).singleElement().satisfies(value -> assertThat(value.captainUserId()).isEqualTo(recipient.captainUserId()));
            readsOnly(recipient.captainUserId(), delivered.getFirst());
        }
        for (var other : outsideGroup) readsNothing(other.captainUserId());

        // Each delivery is acknowledged independently and only by its own captain.
        var first = issued.findAll().stream().filter(value -> value.busId().equals(recipients.get(0).busId())).findFirst().orElseThrow();
        acknowledge(first.id(), recipients.get(1).captainUserId()).andExpect(status().isNotFound());
        acknowledge(first.id(), recipients.get(0).captainUserId()).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("ACKNOWLEDGED"));
        assertThat(issued.findAll()).filteredOn(value -> value.acknowledgedAt() != null)
                .extracting(IssuedInstruction::id).containsExactly(first.id());
    }

    @Test
    void individualDecisionDeliversOnlyToTheTargetBusCaptain() throws Exception {
        var fleet = groupedFleet();
        var proposal = proposalFor(bus, InstructionType.BE_CAUTIOUS);
        mvc.perform(post("/api/v1/planning/advisories/" + proposal.id() + "/decisions").with(controller())
                .contentType("application/json").content("{}")).andExpect(status().isOk());

        var delivered = issued.findById(proposal.id()).orElseThrow();
        assertThat(delivered.busId()).isEqualTo(bus.busId());
        assertThat(delivered.captainUserId()).isEqualTo(captain);
        readsOnly(captain, delivered);
        for (var other : fleet.subList(1, fleet.size())) readsNothing(other.captainUserId());
    }

    @Test
    void approvedBunchingControlDeliversOnlyToTheTargetBusCaptain() throws Exception {
        var fleet = groupedFleet();
        var recommendation = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        mvc.perform(post("/api/v1/planning/recommendations/" + recommendation + "/obu-decisions").with(controller()))
                .andExpect(status().isOk()).andExpect(jsonPath("busId").value(bus.busId()));

        readsOnly(captain, issued.findById(recommendation).orElseThrow());
        for (var other : fleet.subList(1, fleet.size())) readsNothing(other.captainUserId());
    }

    @Test
    void recommendationApprovalIssuesToCaptainAndAppearsInInstructionHistory() throws Exception {
        var fleet = groupedFleet();
        var recommendation = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        mvc.perform(post("/api/v1/planning/recommendations/" + recommendation + "/decision").with(controller())
                        .contentType("application/json").content("{\"action\":\"APPROVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("action").value("APPROVED"));

        var delivered = issued.findById(recommendation).orElseThrow();
        assertThat(delivered.busId()).isEqualTo(bus.busId());
        mvc.perform(get("/api/v1/planning/instructions").with(controller()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(recommendation.toString()));
        readsOnly(captain, delivered);
        for (var other : fleet.subList(1, fleet.size())) readsNothing(other.captainUserId());
    }

    @Test
    void rejectedRecommendationIssuesOneValidatedInstructionPerAffectedBus() throws Exception {
        groupedFleet();
        var recommendation = bunchingRecommendation(FollowingBusState.READY_TO_DEPART,
                List.of("SBS5898U", "BUS-C", "BUS-D"));
        when(context.bunchingGroup(run, recommendation)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, recommendation, "190", 1, "44251", List.of("SBS5898U", "BUS-C", "BUS-D"), "SBS5898U"));
        String decisionPath = "/api/v1/planning/recommendations/" + recommendation + "/decision";
        String body = """
                {"action":"REJECTED","controllerRationale":"Reduce the bunching safely.",
                 "replacementInstructions":[
                   {"busReference":"SBS5898U","action":"SKIP_STOP"},
                   {"busReference":"BUS-C","action":"HOLD","adjustmentSeconds":45},
                   {"busReference":"BUS-D","action":"HOLD","adjustmentSeconds":30}]}
                """;
        mvc.perform(post(decisionPath).with(controller()).contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The adjustments would make the gap between BUS-C and BUS-D too short"));
        assertThat(issued.findByProposalIdOrderByIssuedAtAsc(recommendation)).isEmpty();
        mvc.perform(post(decisionPath).with(controller()).contentType("application/json")
                        .content(body.replace("\"adjustmentSeconds\":30", "\"adjustmentSeconds\":45")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.overrideInstructions.length()").value(3));

        var issuedForRecommendation = issued.findByProposalIdOrderByIssuedAtAsc(recommendation);
        assertThat(issuedForRecommendation).hasSize(3)
                .extracting(item -> item.busId() + ":" + item.parameters().durationSeconds())
                .containsExactlyInAnyOrder("demo-190-b:null", "demo-190-c:45", "demo-190-d:45");
        mvc.perform(get("/api/v1/planning/instructions").with(controller()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor controller() {
        return jwt().jwt(token -> token.subject("controller")).authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor captainToken(UUID subject) {
        return jwt().jwt(token -> token.subject(subject.toString())).authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"));
    }

    private void readsOnly(UUID captainUserId, IssuedInstruction expected) throws Exception {
        mvc.perform(get("/api/v1/planning/captain/instructions").with(captainToken(captainUserId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.id().toString()))
                .andExpect(jsonPath("$.items[0].busId").value(expected.busId()));
    }

    private void readsNothing(UUID captainUserId) throws Exception {
        mvc.perform(get("/api/v1/planning/captain/instructions").with(captainToken(captainUserId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions acknowledge(UUID id, UUID captainUserId) throws Exception {
        return mvc.perform(post("/api/v1/planning/captain/instructions/" + id + "/acknowledgements").with(captainToken(captainUserId)));
    }

    private List<AdvisoryContextPort.Bus> groupedFleet() {
        var fleet = List.of(bus,
                new AdvisoryContextPort.Bus("demo-190-c", "trip-c", "BUS-C", "190", 1, UUID.randomUUID(), "ASSIGNED", bus.progress()),
                new AdvisoryContextPort.Bus("demo-190-d", "trip-d", "BUS-D", "190", 1, UUID.randomUUID(), "ASSIGNED", bus.progress()),
                new AdvisoryContextPort.Bus("demo-190-reverse", "trip-r", "BUS-R", "190", 2, UUID.randomUUID(), "ASSIGNED", bus.progress()),
                new AdvisoryContextPort.Bus("demo-972-a", "trip-a", "BUS-A", "972", 1, UUID.randomUUID(), "ASSIGNED", bus.progress()));
        captureFleet(fleet);
        return fleet;
    }

    private void captureFleet(List<AdvisoryContextPort.Bus> fleet) {
        var exposures = fleet.stream().map(value -> new AdvisoryContextPort.Exposure(value.busId(), value.serviceId(),
                value.directionId(), "44251", "School", 10, "PROXIMITY", "Unverified road impact")).toList();
        hazard = new AdvisoryContextPort.Hazard(hazard.disruptionId(), hazard.kind(), hazard.status(), hazard.inactiveReason(),
                hazard.classification(), hazard.demoRunId(), hazard.sourceObservationId(), hazard.sourceRevision(), hazard.updatedAt(),
                hazard.facts(), exposures, hazard.limitations());
        sources.put(hazard.disruptionId(), hazard);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", fleet, fleet.size()));
    }

    @Test
    void structuredProposalInstructionAndNoticeSurviveDatabaseRoundTrips() {
        var proposal = proposal(InstructionType.REDUCE_SPEED);
        var loadedProposal = proposals.findById(proposal.id()).orElseThrow();
        assertThat(loadedProposal.context()).isEqualTo(hazard);
        assertThat(loadedProposal.bus()).isEqualTo(bus);
        assertThat(loadedProposal.candidate()).isEqualTo(proposal.candidate());
        var instruction = instructions.decide(proposal.id(), null, null, CONTROLLER, null);
        var reloaded = issued.findById(instruction.id()).orElseThrow();
        assertThat(reloaded.parameters()).isEqualTo(proposal.candidate().parameters());
        assertThat(reloaded.message()).isEqualTo("Reduce speed by 5 km/h for 5 min.");
        assertThat(reloaded.captainUserId()).isEqualTo(captain);
        assertThat(proposals.findById(proposal.id()).orElseThrow().finalCandidate()).isEqualTo(proposal.candidate());
        var notice = draft(hazard, UUID.randomUUID(), MESSAGE);
        var reloadedNotice = publications.findById(notice.id()).orElseThrow();
        assertThat(reloadedNotice.services()).containsExactly("190");
        assertThat(reloadedNotice.locations()).containsExactly("School");
        assertThat(reloadedNotice.classification()).isEqualTo("SIMULATION");
        assertThat(jdbc.queryForObject("SELECT source_evidence->>'sourceEventKey' FROM planning.public_notice", String.class))
                .isEqualTo(hazard.facts().sourceEventKey());
    }

    @Test
    void pendingProposalPageExcludesOlderApprovedRecordsBeforePagination() {
        var approved = proposal(InstructionType.REDUCE_SPEED);
        approved.decide(approved.candidate(), "controller", null, null, NOW);
        proposals.saveAndFlush(approved);
        var pending = proposal(InstructionType.SLOW_DOWN_NEAR_STOPS);

        var page = instructions.proposals(0, 1, true);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(AdvisoryProposal::id).containsExactly(pending.id());
    }

    @Test
    void generatedOpenApiAndRuntimeErrorMatchAdvisoryContracts() throws Exception {
        mvc.perform(get("/v3/api-docs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/planning/advisories'].get.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/AdvisoryPageResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/planning/instructions'].get.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/InstructionPageResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/planning/captain/instructions'].get.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/InstructionPageResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/planning/notices'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ManagedNoticePageResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/planning/public/notices'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PublishedNoticePageResponse"))
                .andExpect(jsonPath("$.components.schemas.AdvisoryPageResponse.properties.items.items.$ref")
                        .value("#/components/schemas/AdvisoryResponse"))
                .andExpect(jsonPath("$.components.schemas.InstructionPageResponse.properties.items.items.$ref")
                        .value("#/components/schemas/InstructionResponse"))
                .andExpect(jsonPath("$.components.schemas.InstructionPageResponse.properties.serverTime.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ManagedNoticePageResponse.properties.items.items.$ref")
                        .value("#/components/schemas/NoticeResponse"))
                .andExpect(jsonPath("$.components.schemas.PublishedNoticePageResponse.properties.items.items.$ref")
                        .value("#/components/schemas/PublishedNoticeResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/planning/advisories/{id}/decisions'].post.security[0].SupabaseJWT").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/planning/advisories/{id}/decisions'].post.responses['404'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.status.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.detail.type").value("string"));

        var assignmentContract = mvc.perform(get("/v3/api-docs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var assignmentApi = JSON.readTree(assignmentContract);
        var assignmentOperation = assignmentApi.path("paths")
                .path("/api/v1/planning/admin/demo-runs/{runId}/buses/{busId}/assignment").path("put");
        var idempotencyParameter = java.util.stream.StreamSupport.stream(
                        assignmentOperation.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asText()))
                .findFirst().orElseThrow();
        assertThat(idempotencyParameter.path("required").asBoolean()).isTrue();
        var pendingOperation = assignmentApi.path("paths")
                .path("/internal/v1/planning/demo-runs/{runId}/pending-assignment-operations").path("get");
        assertThat(pendingOperation.path("security").toString()).contains("IngestionPeerToken")
                .doesNotContain("SupabaseJWT", "DisruptionPeerToken");
        assertThat(pendingOperation.path("responses").has("401")).isTrue();

        var internalContract = mvc.perform(get("/v3/api-docs/planning-internal")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var internalApi = JSON.readTree(internalContract);
        assertThat(internalApi.path("security").toString()).contains("DisruptionPeerToken")
                .doesNotContain("SupabaseJWT", "IngestionPeerToken");

        mvc.perform(post("/api/v1/planning/advisories/" + UUID.randomUUID() + "/decisions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").isNotEmpty());
    }

    @Test
    void noticeContractDocumentsAnonymousReadsAndSuccessfulProtectedMutations() throws Exception {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/planning")) {
            String document = mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var contract = tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
            var publicOperation = contract.path("paths").path("/api/v1/planning/public/notices").path("get");
            assertThat(publicOperation.has("security") ? publicOperation.path("security").size() : contract.path("security").size()).isZero();
            for (String mutation : List.of("publications", "withdrawals", "replacements")) {
                var operation = contract.path("paths").path("/api/v1/planning/notices/{id}/" + mutation).path("post");
                assertThat(operation.path("security").get(0).has("SupabaseJWT")).isTrue();
                assertThat(operation.path("responses").path("200").path("content").path("application/json")
                        .path("schema").path("$ref").asText()).isEqualTo("#/components/schemas/NoticeResponse");
                for (String code : List.of("400", "401", "403", "404")) assertThat(operation.path("responses").has(code)).isTrue();
            }
        }
    }

    @Test
    void failedInstructionInsertRollsBackDecisionAndIdenticalRetryIssuesOnce() {
        var proposal = proposal(InstructionType.REDUCE_SPEED);
        jdbc.execute("ALTER TABLE planning.issued_instruction ADD CONSTRAINT test_reject_instruction CHECK(false) NOT VALID");
        try {
            assertThatThrownBy(() -> instructions.decide(proposal.id(), null, null, CONTROLLER, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE planning.issued_instruction DROP CONSTRAINT test_reject_instruction");
        }
        var unchanged = proposals.findById(proposal.id()).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("PENDING");
        assertThat(unchanged.finalCandidate()).isNull();
        assertThat(modelEvidence.findById(proposal.id()).orElseThrow().finalCandidate()).isNull();
        assertThat(issued.count()).isZero();
        var first = instructions.decide(proposal.id(), null, null, CONTROLLER, null);
        var repeated = instructions.decide(proposal.id(), null, null, CONTROLLER, null);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(issued.count()).isEqualTo(1);
        assertThat(proposals.findById(proposal.id()).orElseThrow().status()).isEqualTo("APPROVED");
    }

    @Test
    void concurrentAcknowledgementsPersistOneReceiptAndCannotChangeItsTimestamp() throws Exception {
        var instruction = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return instructions.acknowledge(instruction.id(), captainActor()); });
            var second = executor.submit(() -> { start.await(); return instructions.acknowledge(instruction.id(), captainActor()); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).acknowledgedAt()).isEqualTo(NOW);
            assertThat(second.get(20, TimeUnit.SECONDS).acknowledgedAt()).isEqualTo(NOW);
        }
        var persisted = issued.findById(instruction.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(persisted.applicableUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(jdbc.queryForObject("SELECT version FROM planning.issued_instruction", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT acknowledgement_subject FROM planning.issued_instruction", String.class)).isEqualTo(captain.toString());
        assertThatThrownBy(() -> instructions.acknowledge(instruction.id(), new PlanningActor(UUID.randomUUID().toString(), PlanningActor.Role.CAPTAIN)))
                .isInstanceOf(AdvisoryNotFoundException.class);
    }

    @Test
    void competingMovementDecisionsLeaveOneWinnerAndAnUndecidedLoser() throws Exception {
        var first = proposal(InstructionType.REDUCE_SPEED);
        var second = proposal(InstructionType.SLOW_DOWN_NEAR_STOPS);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> decideAfter(start, first.id()));
            var b = executor.submit(() -> decideAfter(start, second.id()));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(issued.count()).isEqualTo(1);
        assertThat(proposals.findAll()).extracting(AdvisoryProposal::status).containsExactlyInAnyOrder("APPROVED", "PENDING");
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO planning.issued_instruction
            (id,proposal_id,disruption_id,demo_run_id,bus_id,captain_user_id,type,parameters,message,
             hazard_classification,controller_subject,status,issued_at,acknowledgement_deadline,version)
            SELECT ?,?,disruption_id,demo_run_id,bus_id,captain_user_id,type,parameters,message,
             hazard_classification,controller_subject,status,issued_at,acknowledgement_deadline,0
            FROM planning.issued_instruction LIMIT 1
            """, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(issued.count()).isEqualTo(1);
    }

    @Test
    void draftIdentityCannotOverwritePublishedContentAndPublicationRetryKeepsTimestamp() {
        UUID id = UUID.randomUUID();
        draft(hazard, id, MESSAGE);
        var first = notices.publish(id, "controller");
        var repeated = notices.publish(id, "other-controller");
        assertThat(repeated.publishedAt()).isEqualTo(first.publishedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> draft(hazard, id, "Different content"))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(publications.count()).isEqualTo(1);
        var stored = publications.findById(id).orElseThrow();
        assertThat(stored.message()).isEqualTo(MESSAGE);
        assertThat(stored.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void correctedNoticeReplacesPublicContentAndWithdrawalReplaysDoNotResurrectIt() {
        var live = source(false);
        var original = draft(live, UUID.randomUUID(), MESSAGE);
        notices.publish(original.id(), "controller");
        UUID nextId = UUID.randomUUID();
        var corrected = replaceNotice(original.id(), nextId, live.disruptionId());
        assertThat(corrected.publishedAt()).isEqualTo(NOW);
        var previous = publications.findById(original.id()).orElseThrow();
        assertThat(previous.status()).isEqualTo("WITHDRAWN");
        assertThat(previous.replacementId()).isEqualTo(nextId);
        assertThat(previous.withdrawnAt()).isEqualTo(corrected.publishedAt());
        assertThat(previous.message()).isEqualTo(MESSAGE);
        assertThat(notices.current("OBSERVATION_ONLY", null, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(nextId);
        assertThat(jdbc.queryForObject("SELECT published_by FROM planning.public_notice WHERE id=?", String.class, nextId))
                .isEqualTo("correcting-controller");

        notices.withdraw(nextId);
        notices.withdraw(nextId);
        var replay = replaceNotice(original.id(), nextId, live.disruptionId());
        assertThat(replay.status()).isEqualTo("WITHDRAWN");
        assertThat(replay.publishedAt()).isEqualTo(NOW);
        assertThat(replay.withdrawnAt()).isEqualTo(NOW);
        assertThat(publications.count()).isEqualTo(2);
        assertThat(notices.current("OBSERVATION_ONLY", null, 0, 20)).isEmpty();
        assertThatThrownBy(() -> notices.publish(nextId, "controller")).isInstanceOf(AdvisoryConflictException.class);
    }

    @Test
    void failedCorrectionRollsBackBothInsertionAndWithdrawal() {
        var original = draft(hazard, UUID.randomUUID(), MESSAGE);
        notices.publish(original.id(), "controller");
        for (boolean rejectWithdrawal : List.of(true, false)) {
            UUID nextId = UUID.randomUUID();
            String rejected = rejectWithdrawal ? "id <> '" + original.id() + "' OR status <> 'WITHDRAWN'"
                    : "id <> '" + nextId + "' OR status <> 'PUBLISHED'";
            jdbc.execute("ALTER TABLE planning.public_notice ADD CONSTRAINT test_reject_correction CHECK (" + rejected + ") NOT VALID");
            try {
                assertThatThrownBy(() -> replaceNotice(original.id(), nextId, hazard.disruptionId()))
                        .isInstanceOf(DataIntegrityViolationException.class);
            } finally {
                jdbc.execute("ALTER TABLE planning.public_notice DROP CONSTRAINT test_reject_correction");
            }
            assertThat(publications.count()).isEqualTo(1);
            var stored = publications.findById(original.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo("PUBLISHED");
            assertThat(stored.replacementId()).isNull();
        }
    }

    @Test
    void identicalConcurrentCorrectionsReturnOnePublishedRecord() throws Exception {
        var original = draft(hazard, UUID.randomUUID(), MESSAGE);
        notices.publish(original.id(), "controller");
        UUID nextId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return replaceNotice(original.id(), nextId, hazard.disruptionId()).id(); });
            var b = executor.submit(() -> { start.await(); return replaceNotice(original.id(), nextId, hazard.disruptionId()).id(); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(nextId);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(nextId);
        }
        assertThat(publications.count()).isEqualTo(2);
        assertThat(publications.findById(original.id()).orElseThrow().replacementId()).isEqualTo(nextId);
        Long version = jdbc.queryForObject("SELECT version FROM planning.public_notice WHERE id=?", Long.class, nextId);
        replaceNotice(original.id(), nextId, hazard.disruptionId());
        assertThat(jdbc.queryForObject("SELECT version FROM planning.public_notice WHERE id=?", Long.class, nextId)).isEqualTo(version);
    }

    @Test
    void competingCorrectionsLeaveOneWinnerAndNoOrphanDraft() throws Exception {
        var original = draft(hazard, UUID.randomUUID(), MESSAGE);
        notices.publish(original.id(), "controller");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> correctAfter(start, original.id(), UUID.randomUUID()));
            var b = executor.submit(() -> correctAfter(start, original.id(), UUID.randomUUID()));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(publications.count()).isEqualTo(2);
        UUID winner = publications.findById(original.id()).orElseThrow().replacementId();
        assertThat(publications.findById(winner).orElseThrow().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void withdrawalRacingCorrectionKeepsOneConsistentOutcome() throws Exception {
        var original = draft(hazard, UUID.randomUUID(), MESSAGE);
        notices.publish(original.id(), "controller");
        UUID nextId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        boolean corrected;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> correctAfter(start, original.id(), nextId));
            var b = executor.submit(() -> { start.await(); return notices.withdraw(original.id()).status(); });
            start.countDown();
            corrected = a.get(20, TimeUnit.SECONDS);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo("WITHDRAWN");
        }
        var stored = publications.findById(original.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("WITHDRAWN");
        assertThat(publications.count()).isEqualTo(corrected ? 2 : 1);
        assertThat(stored.replacementId()).isEqualTo(corrected ? nextId : null);
    }

    @Test
    void publicationRacingWithdrawalCannotRepublishTheWithdrawnNotice() throws Exception {
        var original = draft(hazard, UUID.randomUUID(), MESSAGE);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                start.await();
                try { notices.publish(original.id(), "controller"); }
                catch (AdvisoryConflictException expected) { /* Withdrawal won the row lock. */ }
                return true;
            });
            var b = executor.submit(() -> { start.await(); return notices.withdraw(original.id()).status(); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo("WITHDRAWN");
        }
        assertThat(publications.findById(original.id()).orElseThrow().status()).isEqualTo("WITHDRAWN");
        assertThatThrownBy(() -> notices.publish(original.id(), "controller")).isInstanceOf(AdvisoryConflictException.class);
    }

    private PublicNotice replaceNotice(UUID previous, UUID next, UUID source) {
        return notices.replace(previous, next, source, "Corrected notice", "Reviewed correction.", List.of("190"), List.of("School"),
                NOW.minusSeconds(1), NOW.plusSeconds(300), "correcting-controller");
    }

    private boolean correctAfter(CountDownLatch start, UUID previous, UUID next) throws InterruptedException {
        start.await();
        try { replaceNotice(previous, next, hazard.disruptionId()); return true; }
        catch (AdvisoryConflictException conflict) { return false; }
    }

    @Test
    void closedRunPreviewsStayIsolatedFromLiveAndOtherRunsUntilExpiryOrWithdrawal() {
        var first = draft(hazard, UUID.randomUUID(), "Run A demonstration");
        notices.publish(first.id(), "controller");
        var liveSource = source(false);
        var live = draft(liveSource, UUID.randomUUID(), "Live passenger notice");
        notices.publish(live.id(), "controller");
        var another = source(true);
        UUID otherRun = UUID.randomUUID();
        var otherSource = new AdvisoryContextPort.Hazard(another.disruptionId(), another.kind(), another.status(), null,
                "SIMULATION", otherRun, another.sourceObservationId(), NOW, another.facts(), another.exposures(), another.limitations());
        sources.put(otherSource.disruptionId(), otherSource);
        var second = draft(otherSource, UUID.randomUUID(), "Run B demonstration");
        notices.publish(second.id(), "controller");

        reconciliation.runClosed(run);
        assertThat(notices.current("OBSERVATION_ONLY", null, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(live.id());
        assertThat(notices.current("SIMULATION", run, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(first.id());
        assertThat(notices.current("SIMULATION", otherRun, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(second.id());
        notices.withdraw(first.id());
        assertThat(notices.current("SIMULATION", run, 0, 20)).isEmpty();
        assertThat(notices.current("SIMULATION", otherRun, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(second.id());
    }

    @Test
    void anonymousScopeErrorsNeverFallBackToAnotherNoticeScope() throws Exception {
        for (String query : List.of("classification=LIVE", "classification=SIMULATION", "classification=SIMULATION&demoRunId=bad-id",
                "classification=OBSERVATION_ONLY&demoRunId=" + run)) {
            mvc.perform(get("/api/v1/planning/public/notices?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void anonymousPublicResponseContainsPassengerContentWithoutManagementMetadata() throws Exception {
        var live = source(false);
        var visible = draft(live, UUID.randomUUID(), MESSAGE);
        notices.publish(visible.id(), "private-controller-subject");
        draft(live, UUID.randomUUID(), "Private draft message");
        var response = mvc.perform(get("/api/v1/planning/public/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visible.id().toString()))
                .andExpect(jsonPath("$.items[0].message").value(MESSAGE))
                .andExpect(jsonPath("$.items[0].services[0]").value("190"))
                .andExpect(jsonPath("$.items[0].locations[0]").value("School"))
                .andExpect(jsonPath("$.items[0].publishedAt").value(NOW.toString()));
        for (String field : List.of("disruptionId", "demoRunId", "createdBy", "publishedBy", "sourceEvidence", "replacementId", "status", "version"))
            response.andExpect(jsonPath("$.items[0]." + field).doesNotExist());
    }

    @Test
    void publicReadsExcludeDraftsWithdrawalsExpiredAndSyntheticNotices() {
        var live = source(false);
        var visible = draft(live, UUID.randomUUID(), MESSAGE); notices.publish(visible.id(), "controller");
        draft(live, UUID.randomUUID(), "Draft only");
        var withdrawn = draft(live, UUID.randomUUID(), "Withdrawn");
        notices.publish(withdrawn.id(), "controller"); notices.withdraw(withdrawn.id());
        var expired = new PublicNotice(UUID.randomUUID(), live, "Expired", "Expired notice", List.of("190"), List.of("School"),
                NOW.minusSeconds(120), NOW, "controller", NOW.minusSeconds(120));
        expired.publish("controller", NOW.minusSeconds(60)); publications.saveAndFlush(expired);
        var simulated = draft(hazard, UUID.randomUUID(), "Simulation notice"); notices.publish(simulated.id(), "controller");
        var scheduled = notices.draft(UUID.randomUUID(), live.disruptionId(), "Scheduled notice", "Future notice", List.of(), List.of(),
                NOW.plusSeconds(1), NOW.plusSeconds(600), "controller");
        notices.publish(scheduled.id(), "controller");
        assertThat(notices.current("OBSERVATION_ONLY", null, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(visible.id());
        assertThat(notices.current("SIMULATION", run, 0, 20).getContent())
                .extracting(PublicNotice::id).containsExactly(simulated.id());
        assertThat(notices.current("SIMULATION", UUID.randomUUID(), 0, 20).getContent()).isEmpty();
    }

    @Test
    void sourceCancellationWithdrawsNoticeAndCancelsInstructionInOneTransaction() {
        var instruction = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        var notice = draft(hazard, UUID.randomUUID(), MESSAGE); notices.publish(notice.id(), "controller");
        jdbc.execute("ALTER TABLE planning.public_notice ADD CONSTRAINT test_reject_withdrawal CHECK(status <> 'WITHDRAWN') NOT VALID");
        try {
            assertThatThrownBy(() -> reconciliation.sourceCleared(hazard.disruptionId(), "SOURCE_CANCELLED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE planning.public_notice DROP CONSTRAINT test_reject_withdrawal");
        }
        assertThat(issued.findById(instruction.id()).orElseThrow().status()).isEqualTo("ISSUED");
        assertThat(publications.findById(notice.id()).orElseThrow().status()).isEqualTo("PUBLISHED");
        reconciliation.sourceCleared(hazard.disruptionId(), "SOURCE_CANCELLED");
        reconciliation.sourceCleared(hazard.disruptionId(), "SOURCE_CANCELLED");
        assertThat(issued.findById(instruction.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(publications.findById(notice.id()).orElseThrow().status()).isEqualTo("WITHDRAWN");
        assertThat(publications.findById(notice.id()).orElseThrow().withdrawnAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select count(*) from planning.application_audit_event where source_key=?", Integer.class,
                "instruction-state:" + instruction.id() + ":CANCELLED:SOURCE_WITHDRAWN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from planning.application_audit_event where source_key=?", Integer.class,
                "public-notice:" + notice.id() + ":NOTICE_WITHDRAWN")).isEqualTo(1);
    }

    @Test
    void closedRunRetiresAcknowledgedStopInstructionButKeepsModelAndDecisionEvidence() {
        var proposal = proposal(InstructionType.SLOW_DOWN_NEAR_STOPS);
        var issuedInstruction = instructions.decide(proposal.id(), null, null, CONTROLLER, null);
        var pendingProposal = proposal(InstructionType.BE_CAUTIOUS);
        instructions.acknowledge(issuedInstruction.id(), captainActor());
        jdbc.update("UPDATE planning.issued_instruction SET issued_at=?, acknowledgement_deadline=?, acknowledged_at=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))),
                Timestamp.from(NOW.minus(Duration.ofHours(13)).plus(Duration.ofMinutes(5))),
                Timestamp.from(NOW.minus(Duration.ofHours(13)).plus(Duration.ofMinutes(1))), issuedInstruction.id());
        jdbc.update("UPDATE planning.advisory_proposal SET created_at=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))), proposal.id());
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "CLOSED", List.of(), 0));

        instructionRuns.reconcileClosedRuns();
        assertThat(issued.findById(issuedInstruction.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(proposals.findById(pendingProposal.id()).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select details->>'reason' from planning.application_audit_event where source_key=?", String.class,
                "advisory-proposal-state:" + pendingProposal.id() + ":EXPIRED")).isEqualTo("RUN_CLOSED");
        retention.purge(Duration.ofHours(12));

        assertThat(issued.existsById(issuedInstruction.id())).isFalse();
        assertThat(jdbc.queryForObject("select details->>'reason' from planning.application_audit_event where source_key=?", String.class,
                "instruction-state:" + issuedInstruction.id() + ":CANCELLED:RUN_CLOSED")).isEqualTo("RUN_CLOSED");
        assertThat(proposals.existsById(proposal.id())).isFalse();
        var evidence = modelEvidence.findById(proposal.id()).orElseThrow();
        assertThat(evidence.modelInput()).isEqualTo("captured input");
        assertThat(evidence.modelOutput()).isEqualTo("captured output");
        assertThat(evidence.candidate()).isEqualTo(proposal.candidate());
        assertThat(evidence.finalCandidate()).isEqualTo(proposal.candidate());
        assertThat(evidence.controllerSubject()).isEqualTo("controller");
        assertThat(evidence.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void retentionExpiryAuditsInstructionTransitionBeforeOperationalDeletion() {
        var proposal = proposal(InstructionType.SLOW_DOWN_NEAR_STOPS);
        var instruction = instructions.decide(proposal.id(), null, null, CONTROLLER, null);
        jdbc.update("UPDATE planning.issued_instruction SET issued_at=?, acknowledgement_deadline=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))), Timestamp.from(NOW.minusSeconds(1)), instruction.id());

        retention.purge(Duration.ofHours(12));

        assertThat(issued.existsById(instruction.id())).isFalse();
        assertThat(jdbc.queryForObject("select details->>'reason' from planning.application_audit_event where source_key=?", String.class,
                "instruction-state:" + instruction.id() + ":EXPIRED:VALIDITY_EXPIRED")).isEqualTo("VALIDITY_EXPIRED");
        assertThat(jdbc.queryForObject("select details->>'instructionType' from planning.application_audit_event where source_key=?", String.class,
                "instruction-state:" + instruction.id() + ":EXPIRED:VALIDITY_EXPIRED")).isEqualTo("SLOW_DOWN_NEAR_STOPS");
    }

    @Test
    void retentionExpiryAuditsPendingProposalBeforeOperationalDeletion() {
        var proposal = proposal(InstructionType.BE_CAUTIOUS);
        jdbc.update("UPDATE planning.advisory_proposal SET created_at=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))), proposal.id());

        retention.purge(Duration.ofHours(12));

        assertThat(proposals.existsById(proposal.id())).isFalse();
        assertThat(jdbc.queryForObject("select details->>'reason' from planning.application_audit_event where source_key=?", String.class,
                "advisory-proposal-state:" + proposal.id() + ":EXPIRED:RETENTION_EXPIRED")).isEqualTo("RETENTION_EXPIRED");
        assertThat(jdbc.queryForObject("select details->>'status' from planning.application_audit_event where source_key=?", String.class,
                "advisory-proposal-state:" + proposal.id() + ":EXPIRED:RETENTION_EXPIRED")).isEqualTo("EXPIRED");
    }

    @Test
    void holdAtomicallyReplacesExplicitMovementAndReplaysOnlyTheSamePredecessor() throws Exception {
        var predecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        mvc.perform(post("/api/v1/planning/recommendations/" + id + "/obu-decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .contentType("application/json").content("{\"replaceInstructionId\":\"" + predecessor.id() + "\"}"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk()).andExpect(jsonPath("type").value("HOLD"));
        assertThat(issued.findById(predecessor.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from planning.application_audit_event where source_key=?", Integer.class,
                "bunching-decision:" + id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select bus_id from planning.application_audit_event where source_key=?", String.class,
                "bunching-decision:" + id)).isEqualTo("SBS5898U");
        assertThat(jdbc.queryForObject("select correlation_id from planning.application_audit_event where source_key=?", String.class,
                "bunching-decision:" + id)).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("select count(*) from planning.application_audit_event where source_key=?", Integer.class,
                "instruction-state:" + predecessor.id() + ":CANCELLED:CONTROLLER_REPLACED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from planning.application_audit_event where source_key=?", Integer.class,
                "instruction-state:" + id + ":ISSUED:null")).isEqualTo(1);
        mvc.perform(post("/api/v1/planning/recommendations/" + id + "/obu-decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .contentType("application/json").content("{\"replaceInstructionId\":\"" + predecessor.id() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(id.toString()));
        mvc.perform(post("/api/v1/planning/recommendations/" + id + "/obu-decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isConflict());
        assertThat(issued.count()).isEqualTo(2);
    }

    @Test
    void controllerCanIssueAndReplayFourBusInstructions() {
        var buses = new java.util.ArrayList<AdvisoryContextPort.Bus>();
        buses.add(bus);
        for (int index = 1; index <= 3; index++) {
            buses.add(new AdvisoryContextPort.Bus("demo-190-" + index, "trip-" + index, "REG-" + index,
                    "190", 1, UUID.randomUUID(), "ASSIGNED",
                    bus.progress()));
        }
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", buses, buses.size()));
        var references = buses.stream().map(AdvisoryContextPort.Bus::registrationNumber).toList();
        var recommendationId = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, recommendationId)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, recommendationId, "190", 1, "44251", references, "SBS5898U"));
        var replacements = references.stream().map(reference ->
                reference.equals(references.getFirst())
                        ? new BunchingInstructionService.ReplacementBusInstruction(reference, MitigationType.SKIP_STOP, 0)
                        : new BunchingInstructionService.ReplacementBusInstruction(reference, MitigationType.HOLD, 30)).toList();

        var delivered = bunchingInstructions.rejectAndIssue(recommendationId, CONTROLLER, replacements, null);
        assertThat(delivered).extracting(IssuedInstruction::busId)
                .containsExactlyInAnyOrderElementsOf(buses.stream().map(AdvisoryContextPort.Bus::busId).toList());
        assertThat(bunchingInstructions.rejectAndIssue(recommendationId, CONTROLLER, replacements, null))
                .extracting(IssuedInstruction::id).containsExactlyInAnyOrderElementsOf(delivered.stream().map(IssuedInstruction::id).toList());
        assertThat(jdbc.queryForObject("select jsonb_array_length(override_instructions) from planning.recommendation_decision where recommendation_id = ?",
                Integer.class, recommendationId)).isEqualTo(4);
    }

    @Test
    void everyAffectedBusNeedsAnOutcomeAndNoInstructionReasonsAreAudited() throws Exception {
        var references = List.of("bus-2", "bus-3", "bus-4", "SBS5898U");
        var fleet = new java.util.ArrayList<AdvisoryContextPort.Bus>();
        fleet.add(bus);
        for (int index = 2; index <= 4; index++) {
            fleet.add(new AdvisoryContextPort.Bus("bus-" + index, "trip-" + index, "REG-" + index,
                    "190", 1, null, "UNASSIGNED", null));
        }
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", fleet, fleet.size()));
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, id, "190", 1, "44251", references, "SBS5898U"));
        var outcomes = List.of(new BunchingInstructionService.ReplacementBusInstruction("SBS5898U", MitigationType.HOLD, 30),
                new BunchingInstructionService.ReplacementBusInstruction("bus-2", null, 0, "Continue normally"),
                new BunchingInstructionService.ReplacementBusInstruction("bus-3", null, 0, "Already spaced"),
                new BunchingInstructionService.ReplacementBusInstruction("bus-4", null, 0, "No action needed"));

        assertThatThrownBy(() -> bunchingInstructions.approveAndIssue(id, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes.subList(0, 3), null))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.count()).isZero();
        String body = """
                {"action":"REJECTED","replacementInstructions":[
                  {"busReference":"SBS5898U","action":"HOLD","adjustmentSeconds":30},
                  {"busReference":"bus-2","action":"NO_INSTRUCTION","noInstructionReason":"Continue normally"},
                  {"busReference":"bus-3","action":"NO_INSTRUCTION","noInstructionReason":"Already spaced"},
                  {"busReference":"bus-4","action":"NO_INSTRUCTION","noInstructionReason":"No action needed"}]}
                """;
        mvc.perform(post("/api/v1/planning/recommendations/{id}/decision", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.overrideInstructions.length()").value(4));
        var delivered = issued.findByProposalIdOrderByIssuedAtAsc(id);
        assertThat(delivered).singleElement().satisfies(item -> assertThat(item.busId()).isEqualTo(bus.busId()));
        mvc.perform(post("/api/v1/planning/recommendations/{id}/decision", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(issued.findByProposalIdOrderByIssuedAtAsc(id)).extracting(IssuedInstruction::id)
                .containsExactly(delivered.getFirst().id());
        assertThat(jdbc.queryForObject("select override_instructions::text from planning.recommendation_decision where recommendation_id = ?",
                String.class, id)).contains("NO INSTRUCTION: Continue normally", "NO INSTRUCTION: Already spaced", "NO INSTRUCTION: No action needed");
    }

    @Test
    void changedAffectedGroupRollsBackTheDecisionAndDeliveries() {
        var references = List.of("SBS5898U", "bus-2");
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, id, "190", 1, "44251", List.of("SBS5898U", "bus-3"), "SBS5898U"));
        var outcomes = List.of(new BunchingInstructionService.ReplacementBusInstruction("SBS5898U", MitigationType.HOLD, 30),
                new BunchingInstructionService.ReplacementBusInstruction("bus-2", null, 0, "Continue"));

        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("group changed");
        assertThat(issued.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void latestGroupChangesOrUnavailableEvidenceRollBackTheWholeDecision() {
        var references = List.of("SBS5898U", "bus-2");
        var other = new AdvisoryContextPort.Bus("bus-2", "trip-2", "REG-2", "190", 1,
                null, "UNASSIGNED", null);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus, other), 2));
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, id, "190", 1, "44251", references, "SBS5898U"));
        var outcomes = List.of(new BunchingInstructionService.ReplacementBusInstruction("SBS5898U", MitigationType.HOLD, 30),
                new BunchingInstructionService.ReplacementBusInstruction("bus-2", null, 0, "Continue"));
        when(context.currentBunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.CurrentBunchingGroup(
                run, id, "190", "44251", 1, UUID.randomUUID(), NOW, NOW.plusSeconds(60), List.of("bus-2", "demo-190-b")));
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("group changed");
        when(context.currentBunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.CurrentBunchingGroup(
                run, id, "190", "44251", 1, UUID.randomUUID(), NOW.minusSeconds(60), NOW, List.of("demo-190-b", "bus-2")));
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("expired");
        when(context.currentBunchingGroup(run, id)).thenThrow(new IllegalStateException("synthetic unavailable"));
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("unavailable");
        assertThat(issued.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void singleBusRecommendationCannotBypassTheCurrentMultiBusGroup() {
        var other = new AdvisoryContextPort.Bus("bus-2", "trip-2", "REG-2", "190", 1,
                null, "UNASSIGNED", null);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus, other), 2));
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        when(context.currentBunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.CurrentBunchingGroup(
                run, id, "190", "44251", 1, UUID.randomUUID(), NOW, NOW.plusSeconds(60), List.of("demo-190-b", "bus-2")));
        assertThatThrownBy(() -> bunchingInstructions.approveAndIssue(id, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("group changed");
        assertThat(issued.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void supersedingRecommendationRetainsHistoryAndPreventsDecisionsOnThePreviousVersion() {
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var original = records.findByIdempotencyKey(id).orElseThrow();
        var nextId = UUID.randomUUID();
        var replacement = revisionOf(original, nextId);
        UUID snapshot = UUID.randomUUID();

        assertThat(records.supersede(id, replacement, snapshot)).isTrue();
        assertThat(records.findRevision(id)).contains(new RecommendationRecordPort.Revision(nextId, snapshot));
        assertThat(records.findByIdempotencyKey(id)).isPresent();
        assertThat(records.findByIdempotencyKey(nextId)).isPresent();
        assertThat(records.findPending(0, 20)).extracting(RecommendationRecord::idempotencyKey).containsExactly(nextId);
        assertThat(records.findByBunchingEventId(id)).get().extracting(RecommendationRecord::idempotencyKey).isEqualTo(nextId);
        assertThat(records.supersede(id, replacement, snapshot)).isFalse();
        assertThatThrownBy(() -> bunchingInstructions.approveAndIssue(id, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("superseded");
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, id)).isZero();
        jdbc.update("DELETE FROM planning.recommendation WHERE recommendation_id = ?", id);
        assertThat(records.findByIdempotencyKey(nextId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT revision_raw_observation_id FROM planning.recommendation WHERE recommendation_id = ?",
                UUID.class, nextId)).isEqualTo(snapshot);
    }

    @Test
    void concurrentRevisionsProduceOnlyOneSuccessor() throws Exception {
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var original = records.findByIdempotencyKey(id).orElseThrow();
        var first = revisionOf(original, UUID.randomUUID());
        var second = revisionOf(original, UUID.randomUUID());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return records.supersede(id, first, UUID.randomUUID()); });
            var b = executor.submit(() -> { start.await(); return records.supersede(id, second, UUID.randomUUID()); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(records.findRevision(id)).isPresent();
        assertThat(records.findPending(0, 20)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation", Integer.class)).isEqualTo(2);
    }

    @Test
    void decidedRecommendationCannotBeSuperseded() {
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var original = records.findByIdempotencyKey(id).orElseThrow();
        var replacement = revisionOf(original, UUID.randomUUID());
        jdbc.update("INSERT INTO planning.recommendation_decision (recommendation_id, action, actor_subject, decided_at, override_instructions) VALUES (?, 'APPROVED', 'controller', ?, '[]'::jsonb)",
                id, Timestamp.from(NOW));
        assertThat(records.supersede(id, replacement, UUID.randomUUID())).isFalse();
        assertThat(records.findByIdempotencyKey(replacement.idempotencyKey())).isEmpty();
        assertThat(records.findRevision(id)).isEmpty();
    }

    @Test
    void concurrentDecisionAndRevisionCannotBothWin() throws Exception {
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var replacement = revisionOf(records.findByIdempotencyKey(id).orElseThrow(), UUID.randomUUID());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var revision = executor.submit(() -> { start.await(); return records.supersede(id, replacement, UUID.randomUUID()); });
            var decision = executor.submit(() -> {
                start.await();
                try { bunchingInstructions.approveAndIssue(id, CONTROLLER); return true; }
                catch (AdvisoryConflictException conflict) { return false; }
            });
            start.countDown();
            assertThat(List.of(revision.get(10, TimeUnit.SECONDS), decision.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(records.findRevision(id).isPresent())
                .isNotEqualTo(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, id) == 1);
    }

    private RecommendationRecord revisionOf(RecommendationRecord original, UUID id) {
        var updated = new BunchingRecommendationPolicy().evaluate(id, original.classification(),
                original.bunchingEvent(), original.diagnosis(), original.mitigationContext(),
                original.recommendation().expectedImpact()).orElseThrow();
        return new RecommendationRecord(id, "b".repeat(64), original.requestId(), original.classification(),
                original.bunchingEvent(), original.diagnosis(), original.mitigationContext(), updated, NOW.plusSeconds(1));
    }

    @Test
    void allBusesCanHaveNoInstructionAndTheDecisionCanBeReplayed() {
        var references = List.of("SBS5898U", "bus-2");
        var other = new AdvisoryContextPort.Bus("bus-2", "trip-2", "REG-2", "190", 1,
                null, "UNASSIGNED", null);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus, other), 2));
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, id, "190", 1, "44251", references, "SBS5898U"));
        var outcomes = List.of(new BunchingInstructionService.ReplacementBusInstruction("SBS5898U", null, 0, "Already spaced"),
                new BunchingInstructionService.ReplacementBusInstruction("bus-2", null, 0, "Continue"));

        assertThat(bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null)).isEmpty();
        assertThat(bunchingInstructions.rejectAndIssue(id, CONTROLLER, outcomes, null)).isEmpty();
        assertThat(issued.count()).isZero();
        assertThat(jdbc.queryForObject("select jsonb_array_length(override_instructions) from planning.recommendation_decision where recommendation_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void skipStopIsIssuedToTheCorrectCaptainOnlyWhenTheStopIsAhead() throws Exception {
        var references = List.of("SBS5898U", "bus-2");
        var other = new AdvisoryContextPort.Bus("bus-2", "trip-2", "REG-2", "190", 1,
                null, "UNASSIGNED", null);
        var unverifiedProgress = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), "190", 1,
                captain, "ASSIGNED", new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(), List.of()));
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(unverifiedProgress, other), 2));
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART, references);
        when(context.bunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.BunchingGroup(
                run, id, "190", 1, "44251", references, "SBS5898U"));
        String body = """
                {"action":"REJECTED","replacementInstructions":[
                  {"busReference":"SBS5898U","action":"SKIP_STOP"},
                  {"busReference":"bus-2","action":"NO_INSTRUCTION","noInstructionReason":"Continue normally"}]}
                """;
        mvc.perform(post("/api/v1/planning/recommendations/{id}/decision", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content(body)).andExpect(status().isConflict());
        assertThat(issued.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?",
                Integer.class, id)).isZero();

        for (var progress : List.of(
                new AdvisoryContextPort.Progress("44251", 10, NOW.minusSeconds(120), 30, List.of(), List.of(), NOW.plusSeconds(60), NOW),
                new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(), List.of(), NOW, NOW.plusSeconds(120)),
                new AdvisoryContextPort.Progress("44251", 10, NOW.plusSeconds(1), 30, List.of(), List.of(), NOW.plusSeconds(60), NOW.plusSeconds(120)))) {
            var unverified = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), "190", 1,
                    captain, "ASSIGNED", progress);
            when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(unverified, other), 2));
            mvc.perform(post("/api/v1/planning/recommendations/{id}/decision", id)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                            .contentType("application/json").content(body)).andExpect(status().isConflict());
            assertThat(issued.count()).isZero();
        }
        var approaching = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), "190", 1,
                captain, "ASSIGNED", new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(), List.of(),
                        NOW.plusSeconds(60), NOW.plusSeconds(120)));
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(approaching, other), 2));
        mvc.perform(post("/api/v1/planning/recommendations/{id}/decision", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content(body)).andExpect(status().isOk());
        var instruction = issued.findByProposalIdOrderByIssuedAtAsc(id).getFirst();
        assertThat(instruction.type()).isEqualTo(InstructionType.SKIP_STOP);
        assertThat(instruction.parameters().stopCode()).isEqualTo("44251");
        assertThat(instruction.parameters().durationSeconds()).isNull();
        mvc.perform(get("/api/v1/planning/captain/instructions")
                        .with(jwt().jwt(token -> token.subject(captain.toString())).authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("SKIP_STOP"))
                .andExpect(jsonPath("$.items[0].message").value("Skip stop School."));
        mvc.perform(post("/api/v1/planning/captain/instructions/{id}/acknowledgements", instruction.id())
                        .with(jwt().jwt(token -> token.subject(captain.toString())).authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
    }

    @Test
    void competingReplacementReportsWinningInstructionAndFailedIssueRollsBackCancellation() throws Exception {
        var predecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        var first = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var second = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        var body = "{\"replaceInstructionId\":\"" + predecessor.id() + "\"}";
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var requests = List.of(first, second).stream().map(id -> pool.submit(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/api/v1/planning/recommendations/" + id + "/obu-decisions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType("application/json").content(body)).andReturn().getResponse();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = requests.stream().map(f -> {
                try { return f.get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
            }).toList();
            var success = responses.stream().filter(response -> response.getStatus() == 200).findFirst().orElseThrow();
            var conflict = responses.stream().filter(response -> response.getStatus() == 409).findFirst().orElseThrow();
            var winnerId = issued.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(run, bus.busId(), List.of("ISSUED", "ACKNOWLEDGED"))
                    .stream().filter(i -> i.type() != InstructionType.BE_CAUTIOUS).findFirst().orElseThrow().id();
            assertThat(conflict.getContentAsString()).contains(winnerId.toString());
            assertThat(success.getContentAsString()).contains(winnerId.toString());
        } finally {
            pool.shutdownNow();
        }

        jdbc.execute("TRUNCATE planning.application_audit_event, planning.issued_instruction, planning.advisory_proposal, planning.advisory_model_evidence, planning.recommendation_decision, planning.model_invocation_record, planning.recommendation");
        var rollbackPredecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, CONTROLLER, null);
        var rollbackId = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        jdbc.execute("ALTER TABLE planning.issued_instruction ADD CONSTRAINT test_reject_bunching_instruction CHECK (id <> '"
                + rollbackId + "')");
        try {
            mvc.perform(post("/api/v1/planning/recommendations/" + rollbackId + "/obu-decisions").with(controller())
                            .contentType("application/json").content("{\"replaceInstructionId\":\"" + rollbackPredecessor.id() + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("delivery failed for SBS5898U")));
        } finally {
            jdbc.execute("ALTER TABLE planning.issued_instruction DROP CONSTRAINT test_reject_bunching_instruction");
        }
        assertThat(issued.findById(rollbackPredecessor.id()).orElseThrow().status()).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, rollbackId)).isZero();
    }

    @Test
    void refreshedRecommendationRequiresTheReviewedSnapshotAndEachBusToRespectItsOwnGap() throws Exception {
        var originalId = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        when(recommendationScopes.current()).thenReturn(new PlanningScope("DEMO", run));
        var busReferences = List.of("lead", "middle", "tail");
        var gaps = List.of(590L, 570L);
        var snapshotId = UUID.randomUUID();
        var reviewedGroup = new AdvisoryContextPort.CurrentBunchingGroup(
                run, originalId, "190", "44251", 1, snapshotId, NOW, NOW.plusSeconds(60), busReferences, gaps, List.of(true, true));
        when(context.currentBunchingGroup(run, originalId)).thenReturn(reviewedGroup);
        var progress = new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(5), List.of(300),
                NOW.plusSeconds(60), NOW.plusSeconds(120));
        var fleet = busReferences.stream().map(id -> new AdvisoryContextPort.Bus(
                id, id, id, "190", 1, UUID.randomUUID(), "ASSIGNED", progress)).toList();
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", fleet, fleet.size()));

        mvc.perform(post("/api/v1/planning/recommendations/{id}/refresh", originalId).with(controller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionRawObservationId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.suggestedOutcomes.length()").value(3))
                .andExpect(jsonPath("$.suggestedOutcomes[0].reason").value("No preceding gap in the reviewed group."))
                .andExpect(jsonPath("$.suggestedOutcomes[1].reason").value("The preceding gap already meets the recovery target."))
                .andExpect(jsonPath("$.suggestedOutcomes[1].adjustmentSeconds").value(0))
                .andExpect(jsonPath("$.confirmationGaps[0]").value(276))
                .andExpect(jsonPath("$.currentPredictionGaps[0]").value(590));
        var revisionId = records.findRevision(originalId).orElseThrow().recommendationId();
        var revision = records.findByIdempotencyKey(revisionId).orElseThrow();
        assertThat(refreshes.refresh(originalId)).isEqualTo(revision);
        assertThat(refreshes.refresh(revisionId)).isEqualTo(revision);
        assertThat(records.findByIdempotencyKey(revisionId)).contains(revision);
        assertThat(revision.bunchingEvent().observedHeadwaySeconds()).containsExactly(276, 221);
        assertThat(revision.mitigationContext().affectedBusReferences()).containsExactlyElementsOf(busReferences);
        assertThat(revision.mitigationContext().currentPredictionGaps()).containsExactlyElementsOf(gaps);
        assertThat(revision.recommendation().adjustmentSeconds()).isEqualTo(30);
        assertThat(revision.recommendation().suggestedOutcomes()).containsExactly(
                new SuggestedBusOutcome("lead", null, 0, "No preceding gap in the reviewed group."),
                new SuggestedBusOutcome("middle", null, 0, "The preceding gap already meets the recovery target."),
                new SuggestedBusOutcome("tail", null, 0, "The preceding gap already meets the recovery target."));
        assertThatThrownBy(() -> bunchingInstructions.approveAndIssue(originalId, CONTROLLER))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("superseded");

        var lead = new BunchingInstructionService.ReplacementBusInstruction("lead", null, 0, "Continue");
        var middle = new BunchingInstructionService.ReplacementBusInstruction("middle", MitigationType.HOLD, 10);
        var tail = new BunchingInstructionService.ReplacementBusInstruction("tail", MitigationType.HOLD, 30);
        var outcomes = List.of(lead, middle, tail);
        var newerGroup = new AdvisoryContextPort.CurrentBunchingGroup(
                run, originalId, "190", "44251", 1, UUID.randomUUID(), NOW, NOW.plusSeconds(60), busReferences, gaps, List.of(true, true));
        when(context.currentBunchingGroup(run, originalId)).thenReturn(newerGroup);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("reviewed prediction snapshot");

        var nonconsecutiveGroup = new AdvisoryContextPort.CurrentBunchingGroup(
                run, originalId, "190", "44251", 1, snapshotId, NOW, NOW.plusSeconds(60),
                busReferences, gaps, List.of(true, false));
        when(context.currentBunchingGroup(run, originalId)).thenReturn(nonconsecutiveGroup);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("needs manual review");
        assertThat(issued.findAll()).isEmpty();

        when(context.currentBunchingGroup(run, originalId)).thenReturn(reviewedGroup);
        var excessiveHold = new BunchingInstructionService.ReplacementBusInstruction("middle", MitigationType.HOLD, 11);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(
                revisionId, CONTROLLER, List.of(lead, excessiveHold, tail), null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("gap limit for middle");
        assertThat(issued.findAll()).isEmpty();

        var dwell = new BunchingInstructionService.ReplacementBusInstruction("middle", MitigationType.DWELL_ADJUSTMENT, 10);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, List.of(lead, dwell, tail), null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("boarding evidence for middle");
        var olderProgress = new AdvisoryContextPort.Progress("44251", 10, NOW.minusSeconds(1), 30, List.of(5), List.of(300),
                NOW.plusSeconds(60), NOW.plusSeconds(120));
        var middleCaptain = fleet.get(1).captainUserId();
        var olderBus = new AdvisoryContextPort.Bus("middle", "middle", "middle", "190", 1, middleCaptain,
                "ASSIGNED", olderProgress);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING",
                List.of(fleet.getFirst(), olderBus, fleet.getLast()), fleet.size()));
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("reviewed prediction snapshot for middle");
        assertThat(issued.findAll()).isEmpty();

        var unassigned = new AdvisoryContextPort.Bus("middle", "middle", "middle", "190", 1, middleCaptain,
                "CAPTAIN_ASSIGNMENT_UNAVAILABLE", progress);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING",
                List.of(fleet.getFirst(), unassigned, fleet.getLast()), fleet.size()));
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, outcomes, null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("captain assignment is unavailable for middle");
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", fleet, fleet.size()));

        jdbc.execute("ALTER TABLE planning.issued_instruction ADD CONSTRAINT test_reject_middle CHECK(bus_id <> 'middle')");
        try {
            assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, outcomes, null))
                    .isInstanceOf(BunchingInstructionService.DeliveryException.class).hasMessageContaining("delivery failed for middle");
            assertThat(issued.findAll()).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE planning.issued_instruction DROP CONSTRAINT test_reject_middle");
        }

        var boarding = new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(5), List.of(300),
                NOW, NOW.plusSeconds(120), NOW.plusSeconds(60));
        var boardingBus = new AdvisoryContextPort.Bus("middle", "middle", "middle", "190", 1, fleet.get(1).captainUserId(),
                "ASSIGNED", boarding);
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING",
                List.of(fleet.getFirst(), boardingBus, fleet.getLast()), fleet.size()));
        var excessiveDwell = new BunchingInstructionService.ReplacementBusInstruction("middle", MitigationType.DWELL_ADJUSTMENT, 11);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(
                revisionId, CONTROLLER, List.of(lead, excessiveDwell, tail), null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("gap limit for middle");

        var approachingDwell = new BunchingInstructionService.ReplacementBusInstruction("tail", MitigationType.DWELL_ADJUSTMENT, 10);
        assertThatThrownBy(() -> bunchingInstructions.rejectAndIssue(
                revisionId, CONTROLLER, List.of(lead, dwell, approachingDwell), null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("boarding evidence for tail");

        var mixed = List.of(lead, dwell, tail);
        var deliveries = bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, mixed, null);
        assertThat(deliveries).extracting(IssuedInstruction::type)
                .containsExactlyInAnyOrder(InstructionType.DWELL_ADJUSTMENT, InstructionType.HOLD);
        assertThat(deliveries).extracting(IssuedInstruction::captainUserId)
                .containsExactlyInAnyOrder(fleet.get(1).captainUserId(), fleet.get(2).captainUserId());
        when(context.currentBunchingGroup(run, originalId)).thenReturn(newerGroup);
        assertThat(bunchingInstructions.rejectAndIssue(revisionId, CONTROLLER, mixed, null))
                .extracting(IssuedInstruction::id).containsExactlyInAnyOrderElementsOf(
                        deliveries.stream().map(IssuedInstruction::id).toList());
    }

    private UUID bunchingRecommendation(FollowingBusState state) {
        return bunchingRecommendation(state, List.of("SBS5898U"));
    }
    private UUID bunchingRecommendation(FollowingBusState state, List<String> affectedBusReferences) {
        UUID id = UUID.randomUUID();
        var event = new ConfirmedBunchingEvent(id, "190", 1, "44251", BunchingEventStatus.CONFIRMED,
                600, List.of(276, 221), NOW, List.of("arrival-1", "arrival-2"));
        var diagnosis = new BunchingRootCauseDiagnosis(UUID.randomUUID(), "synthetic scenario evidence", null,
                List.of("scenario-fixture"), List.of("Synthetic demonstration only"));
        var mitigation = new ValidatedMitigationContext("SBS5898U", affectedBusReferences, "44251", state, 120, 75,
                CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED, List.of("scenario-fixture"));
        var recommendation = new BunchingRecommendationPolicy().evaluate(id, SourceClassification.SIMULATION,
                event, diagnosis, mitigation, new SimulatedImpactEstimate(221, 341, 38, 24,
                        new BigDecimal("190"), new BigDecimal("128"))).orElseThrow();
        records.saveIfAbsent(new RecommendationRecord(id, "a".repeat(64), UUID.randomUUID(), SourceClassification.SIMULATION,
                event, diagnosis, mitigation, recommendation, NOW));
        sources.put(id, new AdvisoryContextPort.Hazard(id, "BUNCHING", "ACTIVE", null, "SIMULATION", run,
                null, NOW, null, List.of(), List.of()));
        when(context.currentBunchingGroup(run, id)).thenReturn(new AdvisoryContextPort.CurrentBunchingGroup(
                run, id, "190", "44251", 1, UUID.randomUUID(), NOW, NOW.plusSeconds(60), affectedBusReferences,
                java.util.Collections.nCopies(affectedBusReferences.size() - 1, 221L),
                java.util.Collections.nCopies(affectedBusReferences.size() - 1, true)));
        when(context.route("190")).thenReturn(new AdvisoryContextPort.Route("190", List.of(
                new AdvisoryContextPort.Operator("SBS Transit", "BUS", List.of(
                        new AdvisoryContextPort.Direction(1, List.of(new AdvisoryContextPort.Stop("44251", "School", 10))))))));
        return id;
    }

    private boolean decideAfter(CountDownLatch start, UUID id) throws InterruptedException {
        start.await();
        try { instructions.decide(id, null, null, CONTROLLER, null); return true; }
        catch (AdvisoryConflictException | DataIntegrityViolationException conflict) { return false; }
    }
    private AdvisoryProposal proposal(InstructionType type) {
        return proposalFor(bus, type);
    }
    private AdvisoryProposal proposalFor(AdvisoryContextPort.Bus target, InstructionType type) {
        var parameters = type == InstructionType.REDUCE_SPEED
                ? new InstructionParameters(null, null, null, null, 5, 300)
                : new InstructionParameters("44251", "School", 10, 0, null, null);
        var candidate = new AdvisoryCandidate(type, parameters, "Synthetic scenario evidence");
        String preview = new InstructionPolicy().message(candidate, target, hazard.exposures(), null, context.limits());
        var proposal = proposals.saveAndFlush(new AdvisoryProposal(UUID.randomUUID(), run, hazard, target,
                new AdvisoryDraftPort.Result(candidate, "synthetic-model", "captured input", "captured output"), preview, NOW));
        modelEvidence.saveAndFlush(new AdvisoryModelEvidence(proposal));
        return proposal;
    }
    private PublicNotice draft(AdvisoryContextPort.Hazard source, UUID id, String message) {
        return notices.draft(id, source.disruptionId(), "Reported disruption", message, List.of("190"), List.of("School"),
                NOW.minusSeconds(1), NOW.plusSeconds(300), "controller");
    }
    private AdvisoryContextPort.Hazard source(boolean simulation) {
        UUID id = UUID.randomUUID();
        var facts = new HazardEventFacts.Fact("TRAFFIC_INCIDENT", "incident-" + id, "OBSERVED", NOW,
                null, null, "Reported disruption", "Reported road conditions", null, null, List.of(),
                null, null, null, null, List.of());
        var value = new AdvisoryContextPort.Hazard(id, "TRAFFIC_INCIDENT", "ACTIVE", null,
                simulation ? "SIMULATION" : "OBSERVATION_ONLY", simulation ? run : null, UUID.randomUUID(), NOW,
                facts, List.of(new AdvisoryContextPort.Exposure(bus.busId(), "190", 1, "44251", "School", 10,
                "PROXIMITY", "Unverified road impact")), List.of("Synthetic demonstration"));
        sources.put(id, value); return value;
    }
    @TestConfiguration static class Time {
        @Bean @Primary Clock advisoryTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
