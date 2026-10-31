package com.csd.occ.planning.advisory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.advisory.domain.*;
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
import org.junit.jupiter.api.BeforeEach;
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
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1", "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED=false", "OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false",
        "OCC_PLANNING_GEMINI_API_KEY=test-no-network", "SUPABASE_PROJECT_URL=https://issuer.test",
        "SUPABASE_JWKS_URL=https://issuer.test/auth/v1/jwks"
})
@AutoConfigureMockMvc
@Import(AdvisoryPersistenceIT.Time.class)
@Testcontainers(disabledWithoutDocker = true)
class AdvisoryPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final String MESSAGE = "Service 190 may be delayed near School.";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/create-planning-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired RecommendationRecordPort records;
    @MockitoBean PlanningScopeService recommendationScopes;
    @Autowired InstructionService instructions;
    @Autowired AdvisoryGroupService advisoryGroups;
    @Autowired BunchingInstructionService bunchingInstructions;
    @Autowired PublicNoticeService notices;
    @Autowired AdvisoryReconciliationService reconciliation;
    @Autowired InstructionRunReconciliationService instructionRuns;
    @Autowired AdvisoryRetentionService retention;
    @Autowired AdvisoryProposalRepository proposals;
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
        jdbc.execute("TRUNCATE planning.issued_instruction, planning.advisory_proposal, planning.advisory_model_evidence, planning.public_notice");
        jdbc.execute("TRUNCATE planning.recommendation_decision, planning.model_invocation_record, planning.recommendation");
        sources.clear();
        when(recommendationScopes.includesDiagnosis(any())).thenReturn(true);
        run = UUID.randomUUID(); captain = UUID.randomUUID();
        bus = new AdvisoryContextPort.Bus("demo-190-b", "demo-190-b", "SBS5898U", "190", 1, captain, "ASSIGNED",
                new AdvisoryContextPort.Progress("44251", 10, NOW, 30, List.of(5), List.of(300)));
        hazard = source(true);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", run));
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", List.of(bus), 1));
        when(context.hazard(any())).thenAnswer(invocation -> sources.get(invocation.getArgument(0)));
        when(context.limits()).thenReturn(new AdvisoryContextPort.Limits(10, 900, 10, Duration.ofMinutes(5), Duration.ofHours(12)));
    }

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
        var sent = advisoryGroups.decide(ids, "controller");
        assertThat(sent).hasSize(3);
        assertThat(issued.count()).isEqualTo(3);
        assertThat(advisoryGroups.decide(ids.reversed(), "controller")).extracting(IssuedInstruction::id)
                .containsExactlyInAnyOrderElementsOf(sent.stream().map(IssuedInstruction::id).toList());
        assertThat(issued.count()).isEqualTo(3);
        var one = sent.getFirst();
        var captainId = fleet.stream().filter(value -> value.busId().equals(one.busId())).findFirst().orElseThrow().captainUserId();
        instructions.acknowledge(one.id(), captainId.toString());
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
        assertThat(advisoryGroups.decide(ids, "controller")).hasSize(101);
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
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().map(AdvisoryProposal::id).toList(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.count()).isZero();
        assertThat(proposals.findAll()).allSatisfy(value -> assertThat(value.status()).isEqualTo("PENDING"));
    }

    @Test
    void groupSendRejectsMixedDirectionsIncompleteMembershipAndChangedRoutes() {
        var fleet = groupedFleet();
        var captured = fleet.stream().map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS)).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(List.of(captured.get(0).id(), captured.get(3).id()), "controller"))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().limit(2).map(AdvisoryProposal::id).toList(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class);
        var changed = fleet.stream().map(value -> value.busId().equals(bus.busId())
                ? new AdvisoryContextPort.Bus(value.busId(), value.tripId(), value.registrationNumber(), "972", 1,
                        value.captainUserId(), value.assignmentStatus(), value.progress()) : value).toList();
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "RUNNING", changed, changed.size()));
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().limit(3).map(AdvisoryProposal::id).toList(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("route");
        assertThat(issued.count()).isZero();
    }

    @Test
    void conflictingMovementInstructionPreventsTheWholeGroupSend() {
        groupedFleet();
        var previous = proposalFor(bus, InstructionType.REDUCE_SPEED);
        instructions.decide(previous.id(), null, null, "controller", null);
        hazard = source(true);
        var captured = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.REDUCE_SPEED)).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(captured.stream().map(AdvisoryProposal::id).toList(), "controller"))
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
        assertThatThrownBy(() -> advisoryGroups.decide(ids, "controller"))
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
        assertThatThrownBy(() -> advisoryGroups.decide(ids, "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("no current downstream exposure");
        assertThat(issued.count()).isZero();
    }

    @Test
    void groupSendRejectsDuplicateRecipientsAndAnotherSimulation() {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        assertThatThrownBy(() -> advisoryGroups.decide(List.of(ids.getFirst(), ids.getFirst()), "controller"))
                .isInstanceOf(IllegalArgumentException.class);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", UUID.randomUUID()));
        assertThatThrownBy(() -> advisoryGroups.decide(ids, "controller")).isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.count()).isZero();
    }

    @Test
    void concurrentGroupApprovalsReturnTheSameDeliveries() throws Exception {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<List<UUID>> send = () -> {
                start.await();
                return advisoryGroups.decide(ids, "controller").stream().map(IssuedInstruction::id).toList();
            };
            var first = workers.submit(send);
            var second = workers.submit(send);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).containsExactlyElementsOf(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(issued.count()).isEqualTo(3);
    }

    @Test
    void groupEndpointsAllowControllersAndAdminsAndDenyCaptains() throws Exception {
        var ids = groupedFleet().stream().limit(3).map(value -> proposalFor(value, InstructionType.BE_CAUTIOUS).id()).toList();
        for (String role : List.of("CONTROLLER", "ADMIN")) {
            mvc.perform(get("/api/v1/planning/advisory-groups").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].members.length()").value(3));
        }
        var body = "{\"proposalIds\":[" + ids.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
        for (String role : List.of("CONTROLLER", "ADMIN")) {
            mvc.perform(post("/api/v1/planning/advisory-groups/decisions")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
                    .contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        }
        mvc.perform(get("/api/v1/planning/advisory-groups").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/planning/advisory-groups/decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .contentType("application/json").content("{\"proposalIds\":[]}"))
                .andExpect(status().isForbidden());
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
        var instruction = instructions.decide(proposal.id(), null, null, "controller", null);
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
            assertThatThrownBy(() -> instructions.decide(proposal.id(), null, null, "controller", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE planning.issued_instruction DROP CONSTRAINT test_reject_instruction");
        }
        var unchanged = proposals.findById(proposal.id()).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("PENDING");
        assertThat(unchanged.finalCandidate()).isNull();
        assertThat(modelEvidence.findById(proposal.id()).orElseThrow().finalCandidate()).isNull();
        assertThat(issued.count()).isZero();
        var first = instructions.decide(proposal.id(), null, null, "controller", null);
        var repeated = instructions.decide(proposal.id(), null, null, "controller", null);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(issued.count()).isEqualTo(1);
        assertThat(proposals.findById(proposal.id()).orElseThrow().status()).isEqualTo("APPROVED");
    }

    @Test
    void concurrentAcknowledgementsPersistOneReceiptAndCannotChangeItsTimestamp() throws Exception {
        var instruction = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, "controller", null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return instructions.acknowledge(instruction.id(), captain.toString()); });
            var second = executor.submit(() -> { start.await(); return instructions.acknowledge(instruction.id(), captain.toString()); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).acknowledgedAt()).isEqualTo(NOW);
            assertThat(second.get(20, TimeUnit.SECONDS).acknowledgedAt()).isEqualTo(NOW);
        }
        var persisted = issued.findById(instruction.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(persisted.applicableUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(jdbc.queryForObject("SELECT version FROM planning.issued_instruction", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT acknowledgement_subject FROM planning.issued_instruction", String.class)).isEqualTo(captain.toString());
        assertThatThrownBy(() -> instructions.acknowledge(instruction.id(), UUID.randomUUID().toString()))
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
        var instruction = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, "controller", null);
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
    }

    @Test
    void closedRunRetiresAcknowledgedStopInstructionButKeepsModelAndDecisionEvidence() {
        var proposal = proposal(InstructionType.SLOW_DOWN_NEAR_STOPS);
        var issuedInstruction = instructions.decide(proposal.id(), null, null, "controller", null);
        instructions.acknowledge(issuedInstruction.id(), captain.toString());
        jdbc.update("UPDATE planning.issued_instruction SET issued_at=?, acknowledgement_deadline=?, acknowledged_at=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))),
                Timestamp.from(NOW.minus(Duration.ofHours(13)).plus(Duration.ofMinutes(5))),
                Timestamp.from(NOW.minus(Duration.ofHours(13)).plus(Duration.ofMinutes(1))), issuedInstruction.id());
        jdbc.update("UPDATE planning.advisory_proposal SET created_at=? WHERE id=?",
                Timestamp.from(NOW.minus(Duration.ofHours(13))), proposal.id());
        when(context.fleet(run)).thenReturn(new AdvisoryContextPort.Fleet(run, "CLOSED", List.of(), 0));

        instructionRuns.reconcileClosedRuns();
        assertThat(issued.findById(issuedInstruction.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        retention.purge(Duration.ofHours(12));

        assertThat(issued.existsById(issuedInstruction.id())).isFalse();
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
    void holdAtomicallyReplacesExplicitMovementAndReplaysOnlyTheSamePredecessor() throws Exception {
        var predecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, "controller", null);
        var id = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        mvc.perform(post("/api/v1/planning/recommendations/" + id + "/obu-decisions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .contentType("application/json").content("{\"replaceInstructionId\":\"" + predecessor.id() + "\"}"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk()).andExpect(jsonPath("type").value("HOLD"));
        assertThat(issued.findById(predecessor.id()).orElseThrow().status()).isEqualTo("CANCELLED");
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
    void competingReplacementReportsWinningInstructionAndFailedIssueRollsBackCancellation() throws Exception {
        var predecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, "controller", null);
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

        jdbc.execute("TRUNCATE planning.issued_instruction, planning.advisory_proposal, planning.advisory_model_evidence, planning.recommendation_decision, planning.model_invocation_record, planning.recommendation");
        var rollbackPredecessor = instructions.decide(proposal(InstructionType.REDUCE_SPEED).id(), null, null, "controller", null);
        var rollbackId = bunchingRecommendation(FollowingBusState.READY_TO_DEPART);
        jdbc.execute("ALTER TABLE planning.issued_instruction ADD CONSTRAINT test_reject_bunching_instruction CHECK(false) NOT VALID");
        try {
            assertThatThrownBy(() -> bunchingInstructions.approveAndIssue(rollbackId, "controller", rollbackPredecessor.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE planning.issued_instruction DROP CONSTRAINT test_reject_bunching_instruction");
        }
        assertThat(issued.findById(rollbackPredecessor.id()).orElseThrow().status()).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("select count(*) from planning.recommendation_decision where recommendation_id = ?", Integer.class, rollbackId)).isZero();
    }

    private UUID bunchingRecommendation(FollowingBusState state) {
        UUID id = UUID.randomUUID();
        var event = new ConfirmedBunchingEvent(id, "190", 1, "44251", BunchingEventStatus.CONFIRMED,
                600, List.of(276, 221), NOW, List.of("arrival-1", "arrival-2"));
        var diagnosis = new BunchingRootCauseDiagnosis(UUID.randomUUID(), "synthetic scenario evidence", null,
                List.of("scenario-fixture"), List.of("Synthetic demonstration only"));
        var mitigation = new ValidatedMitigationContext("SBS5898U", "44251", state, 120, 75,
                CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED, List.of("scenario-fixture"));
        var recommendation = new BunchingRecommendationPolicy().evaluate(id, SourceClassification.SIMULATION,
                event, diagnosis, mitigation, new SimulatedImpactEstimate(221, 341, 38, 24,
                        new BigDecimal("190"), new BigDecimal("128"))).orElseThrow();
        records.saveIfAbsent(new RecommendationRecord(id, "a".repeat(64), UUID.randomUUID(), SourceClassification.SIMULATION,
                event, diagnosis, mitigation, recommendation, NOW));
        sources.put(id, new AdvisoryContextPort.Hazard(id, "BUNCHING", "ACTIVE", null, "SIMULATION", run,
                null, NOW, null, List.of(), List.of()));
        when(context.route("190")).thenReturn(new AdvisoryContextPort.Route("190", List.of(
                new AdvisoryContextPort.Operator("SBS Transit", "BUS", List.of(
                        new AdvisoryContextPort.Direction(1, List.of(new AdvisoryContextPort.Stop("44251", "School", 10))))))));
        return id;
    }

    private boolean decideAfter(CountDownLatch start, UUID id) throws InterruptedException {
        start.await();
        try { instructions.decide(id, null, null, "controller", null); return true; }
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
