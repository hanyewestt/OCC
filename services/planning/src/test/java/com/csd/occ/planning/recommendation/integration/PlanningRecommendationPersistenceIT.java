package com.csd.occ.planning.recommendation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.planning.diagnosis.infrastructure.recommendation.PlanningRecommendationAdapter;
import com.csd.occ.planning.recommendation.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendation.domain.ModelInvocationMetadata;
import com.csd.occ.planning.recommendation.domain.ModelInvocationOutcome;
import com.csd.occ.planning.recommendation.domain.ModelInvocationProvider;
import com.csd.occ.planning.recommendation.domain.ModelInvocationRecord;
import com.csd.occ.planning.recommendation.domain.ModelInvocationRecordPort;
import com.csd.occ.planning.domain.audit.ApplicationAuditCapturePort;
import com.csd.occ.planning.recommendation.domain.MitigationCandidate;
import com.csd.occ.planning.recommendation.domain.ModelTokenUsage;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationGeminiProperties;
import com.csd.occ.planning.recommendation.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "OCC_PLANNING_DB_USERNAME=occ_planning",
        "OCC_PLANNING_DB_PASSWORD=synthetic-planning",
        "OCC_PLANNING_DISRUPTION_PEER_TOKEN=synthetic-peer-token",
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED=false",
        "OCC_PLANNING_GEMINI_API_KEY=test-no-network",
        "SUPABASE_PROJECT_URL=https://example.supabase.co",
        "SUPABASE_JWKS_URL=https://example.supabase.co/auth/v1/.well-known/jwks.json"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanningRecommendationPersistenceIT {
    private static final String IDEMPOTENCY_KEY = "f07c921a-bd3b-4a97-8b17-a384ef1269a7";
    private static final Instant TEST_RECORDED_AT = Instant.parse("2026-10-25T02:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ")
            .withUsername("occ_admin")
            .withPassword("synthetic-admin")
            .withInitScript("db/create-planning-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private PlanningRecommendationAdapter planningRecommendations;
    @Autowired private ModelInvocationRecordPort modelInvocations;
    @Autowired private ApplicationAuditCapturePort auditCapture;
    @Autowired private RecommendationAiProperties aiProperties;
    @Autowired private RecommendationGeminiProperties geminiProperties;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext applicationContext;

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "occ.exportOpenapi", matches = "true")
    void exportsGeneratedContract() throws Exception {
        String contract = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("http://localhost:6769/v3/api-docs").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.nio.file.Path output = java.nio.file.Path.of("openapi", "planning.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, contract);
        var packaged = getClass().getResourceAsStream("/static/v3/planning.json");
        org.assertj.core.api.Assertions.assertThat(packaged).as("packaged OpenAPI contract").isNotNull();
        org.assertj.core.api.Assertions.assertThat(new String(packaged.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(contract);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/planning.json")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(contract));
    }

    @Test
    void servesTheStaticContractAndSwaggerUi() throws Exception {
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var ui = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/swagger-ui/index.html").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(ui).doesNotContain("${").doesNotContain("@project.artifactId@")
                .contains("/v3/planning.json");
        var bundle = java.util.regex.Pattern.compile("src=\"(/webjars/swagger-ui/[^\"]+/swagger-ui-bundle\\.js)\"")
                .matcher(ui);
        org.assertj.core.api.Assertions.assertThat(bundle.find()).as("Swagger UI bundle path").isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(bundle.group(1)).with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/planning.json").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.info.title")
                        .value("OCC Planning API"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/swagger-ui.html").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/swagger-ui/index.html"));
    }

    @Test
    void adminAuditEndpointsRequireAdminRole() throws Exception {
        mvc.perform(get("/api/v1/planning/admin/audit-events"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        for (String role : List.of("CONTROLLER", "CAPTAIN")) {
            mvc.perform(get("/api/v1/planning/admin/audit-events")
                            .with(SecurityMockMvcRequestPostProcessors.jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        mvc.perform(get("/api/v1/planning/admin/audit-events")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void adminAuditFiltersBeforePaginationAndUsesOccurrenceThenIdDescendingWithoutDetails() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant occurred = Instant.parse("2026-10-25T01:00:00Z");
        UUID oldest = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID middle = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID newest = UUID.fromString("00000000-0000-0000-0000-000000000030");
        insertAuditEvent(oldest, occurred, "AI_PROCESSING", "FILTER_MATCH", "FAILURE", "actor-a", runId, "entity-a");
        insertAuditEvent(middle, occurred, "AI_PROCESSING", "FILTER_MATCH", "FAILURE", "actor-a", runId, "entity-a");
        insertAuditEvent(newest, occurred, "AI_PROCESSING", "FILTER_MATCH", "FAILURE", "actor-a", runId, "entity-a");
        insertAuditEvent(UUID.randomUUID(), occurred, "OPERATIONS", "FILTER_MATCH", "FAILURE", "actor-a", runId, "entity-a");
        insertAuditEvent(UUID.randomUUID(), occurred, "AI_PROCESSING", "FILTER_MATCH", "SUCCESS", "actor-a", runId, "entity-a");

        var admin = SecurityMockMvcRequestPostProcessors.jwt().jwt(token -> token.subject("admin-subject"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        String filters = "?category=AI_PROCESSING&eventType=FILTER_MATCH&outcome=FAILURE&actorSubject=actor-a"
                + "&runId=" + runId + "&entityId=entity-a&from=2026-10-25T00:00:00Z&to=2026-10-25T02:00:00Z";
        mvc.perform(get("/api/v1/planning/admin/audit-events" + filters + "&page=0&size=2").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(newest.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[1].id").value(middle.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].details").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalItems").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalPages").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.retentionSeconds").isNumber())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.collectionStartedAt").isNotEmpty());
        mvc.perform(get("/api/v1/planning/admin/audit-events" + filters + "&page=1&size=2").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(oldest.toString()));
    }

    @Test
    void adminAiDetailReturnsSanitizedEvidenceAndRecordsOnlySafeAccessMetadata() throws Exception {
        UUID evidenceId = UUID.randomUUID();
        insertAuditEvent(evidenceId, Instant.parse("2026-10-25T01:00:00Z"), "AI_PROCESSING", "AI_TEST", "FAILURE",
                "system", null, evidenceId.toString(), "{\"input\":\"api_key=private-value\",\"output\":\"Bearer private-token\"}");
        var admin = SecurityMockMvcRequestPostProcessors.jwt().jwt(token -> token.subject("verified-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mvc.perform(get("/api/v1/planning/admin/audit-events/" + evidenceId).with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.input").value("api_key=[REDACTED]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.output").value("Bearer [REDACTED]"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.application_audit_event WHERE event_type='AI_EVIDENCE_ACCESSED'", Integer.class))
                .isEqualTo(1);
        UUID accessId = jdbc.queryForObject("SELECT id FROM planning.application_audit_event WHERE event_type='AI_EVIDENCE_ACCESSED'", UUID.class);
        assertThat(jdbc.queryForMap("SELECT actor_subject,details::text AS details,category FROM planning.application_audit_event WHERE id=?", accessId))
                .containsEntry("actor_subject", "verified-admin").containsEntry("category", "OPERATIONS")
                .satisfies(row -> assertThat(row.get("details").toString()).contains(evidenceId.toString())
                        .doesNotContain("private-value", "private-token", "input", "output"));
        mvc.perform(get("/api/v1/planning/admin/audit-events/" + accessId).with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.application_audit_event WHERE event_type='AI_EVIDENCE_ACCESSED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void adminAuditDetailRejectsMissingAndOutOfRetentionEventsWithoutMutation() throws Exception {
        UUID missing = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        insertAuditEvent(expired, Instant.parse("2026-01-01T00:00:00Z"), "AI_PROCESSING", "AI_OLD", "SUCCESS",
                "system", null, expired.toString());
        var admin = SecurityMockMvcRequestPostProcessors.jwt().jwt(token -> token.subject("verified-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get("/api/v1/planning/admin/audit-events/" + missing).with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(get("/api/v1/planning/admin/audit-events/" + expired).with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.application_audit_event WHERE event_type='AI_EVIDENCE_ACCESSED'", Integer.class))
                .isZero();
    }

    @Test
    void servesTheCommittedContractToControllersButNotCaptains() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/planning.json")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/planning.json")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @BeforeEach
    void clearPlanningRecommendationRows() {
        jdbc.update("DELETE FROM planning.application_audit_event");
        jdbc.update("DELETE FROM planning.model_invocation_record");
        jdbc.update("DELETE FROM planning.recommendation");
    }

    @Test
    void keepsReadinessAvailableAndDeniesRemovedMetricsEndpoint() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(get("/actuator/prometheus").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void pendingQueueUsesTheRetainedDiagnosisRunRatherThanAllSimulationHistory() throws Exception {
        String request = validRequest();
        submit(request);
        UUID diagnosisId = UUID.fromString(json.readTree(request).path("diagnosis").path("diagnosisId").asText());
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO planning.planning_request (id,event_type,producer,event_id,schema_version,status,attempt_count,next_attempt_at,created_at,updated_at,version) VALUES (?,'BUS_BUNCHING','disruption',?,'planning-event-v1','COMPLETED',0,now(),now(),now(),0)", requestId, UUID.randomUUID());
        jdbc.update("INSERT INTO planning.diagnosis_result (id,request_id,cause_code,likely_cause,confidence,evidence_support_score,supporting_evidence_ids,limitations,confidence_components,evidence_snapshot,evidence_snapshot_hash,provider,model,prompt_version,schema_version,completed_at) VALUES (?,?,'UNKNOWN','Retained diagnosis',0,0,'[]'::jsonb,'[]'::jsonb,'{}'::jsonb,?::jsonb,?,'GOOGLE_GENAI','synthetic-model','v1','v1',now())",
                diagnosisId, requestId, "{\"event\":{\"classification\":\"SIMULATION\",\"demoRunId\":\"" + runId + "\"}}", "a".repeat(64));
        var records = applicationContext.getBean(com.csd.occ.planning.recommendation.domain.RecommendationRecordPort.class);
        assertThat(records.findPendingForRun(runId, 0, 10)).hasSize(1);
        assertThat(records.countPendingForRun(runId)).isEqualTo(1);
        assertThat(records.findPendingForRun(UUID.randomUUID(), 0, 10)).isEmpty();
        assertThat(records.countPendingForRun(UUID.randomUUID())).isZero();
    }

    @Test
    void deterministicFixtureRunsThroughTheInProcessPlanningAdapter() throws Exception {
        var result = submit(validRequest());
        var response = json.readTree(result.responseJson());

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(response.get("recommendationId").asText()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(response.get("mitigationType").asText()).isEqualTo("HOLD");
        assertThat(response.get("adjustmentSeconds").asInt()).isEqualTo(120);
        assertThat(response.get("targetBusReference").asText()).isEqualTo("SBS5898U");
        assertThat(response.get("controlStopCode").asText()).isEqualTo("44251");
        assertThat(response.get("expectedImpact").get("headwayAfterSeconds").asInt()).isEqualTo(341);
    }

    @Test
    void replayReturnsTheStoredRecommendationWithoutCreatingAnotherRow() throws Exception {
        var first = submit(validRequest());
        var replay = submit(validRequest());

        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.responseJson()).isEqualTo(first.responseJson());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation", Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsRequestContextAndValidatedResponseAsEvidence() throws Exception {
        submit(validRequest());

        assertThat(jdbc.queryForMap("""
                SELECT recommendation_id, bunching_event_id, direction_id, adjustment_seconds,
                       request_payload::text AS request_payload,
                       mitigation_context::text AS mitigation_context,
                       response_payload::text AS response_payload, created_at
                  FROM planning.recommendation
                 WHERE recommendation_id = ?::uuid
                """, IDEMPOTENCY_KEY))
                .satisfies(row -> {
                    assertThat(((Number) row.get("direction_id")).intValue()).isEqualTo(1);
                    assertThat(((Number) row.get("adjustment_seconds")).intValue()).isEqualTo(120);
                    assertThat(row.get("request_payload").toString()).contains("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");
                    assertThat(row.get("mitigation_context").toString()).contains("maximumHoldSeconds");
                    assertThat(row.get("response_payload").toString()).contains("HOLD", "RECOMMENDED");
                    assertThat(row.get("created_at")).isNotNull();
                });
    }

    @Test
    void persistsModelAttemptEvidenceInTheUnifiedTable() throws Exception {
        submit(validRequest());
        modelInvocations.save(acceptedModelInvocation());

        assertThat(jdbc.queryForMap("""
                SELECT purpose, provider, model, prompt_version, output_schema_version,
                       provider_response_id, outcome, candidate_payload::text AS candidate_payload,
                       deterministic_fallback_used, prompt_tokens, completion_tokens,
                       total_tokens, latency_millis, failure_category
                  FROM planning.model_invocation_record
                 WHERE correlation_id = ?::uuid
                """, IDEMPOTENCY_KEY))
                .containsEntry("purpose", "RECOMMENDATION")
                .containsEntry("provider", "GEMINI")
                .containsEntry("model", "gemini-3.1-flash-lite")
                .containsEntry("prompt_version", "bunching-candidate-v1")
                .containsEntry("output_schema_version", "mitigation-candidate-v1")
                .containsEntry("provider_response_id", "synthetic-response-id")
                .containsEntry("outcome", "ACCEPTED")
                .containsEntry("deterministic_fallback_used", false)
                .containsEntry("prompt_tokens", 120)
                .containsEntry("completion_tokens", 25)
                .containsEntry("total_tokens", 145)
                .containsEntry("latency_millis", 250L)
                .containsEntry("failure_category", null)
                .satisfies(row -> assertThat(row.get("candidate_payload").toString())
                        .contains("SBS5898U", "44241", "requestedAdjustmentSeconds")
                        .doesNotContain("API", "key", "prompt"));
    }

    @Test
    void modelAttemptSurvivesAnOuterTransactionRollback() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            modelInvocations.save(acceptedModelInvocation());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id::text FROM planning.model_invocation_record", String.class))
                .isEqualTo(IDEMPOTENCY_KEY);
        assertThat(jdbc.queryForObject("SELECT recommendation_id FROM planning.model_invocation_record", UUID.class))
                .isNull();
    }

    @Test
    void modelInvocationAndItsAuditEventSurviveOuterRollbackAndAuditReplayIsIdempotent() {
        var invocation = acceptedModelInvocation();
        var metadata = invocation.metadata();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            modelInvocations.save(invocation);
            auditCapture.recordRecommendationAttempt(metadata.invocationId(), metadata.completedAt(),
                    invocation.recommendationId(), UUID.fromString(IDEMPOTENCY_KEY), invocation.outcome().name(),
                    java.util.Map.of("provider", metadata.provider().name(), "model", metadata.model(),
                            "input", "api_key=synthetic-secret", "output", "safe output"));
            auditCapture.recordRecommendationAttempt(metadata.invocationId(), metadata.completedAt(),
                    invocation.recommendationId(), UUID.fromString(IDEMPOTENCY_KEY), invocation.outcome().name(),
                    java.util.Map.of("provider", metadata.provider().name(), "model", metadata.model(),
                            "input", "api_key=synthetic-secret", "output", "safe output"));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.application_audit_event WHERE source_key=?", Integer.class,
                "model-invocation:" + metadata.invocationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details::text FROM planning.application_audit_event WHERE source_key=?", String.class,
                "model-invocation:" + metadata.invocationId())).contains("[REDACTED]").doesNotContain("synthetic-secret");
    }

    @Test
    void databaseConstraintsRejectMissingOrMismatchedJsonProjections() throws Exception {
        submit(validRequest());
        for (String mutation : List.of(
                "request_payload #- '{bunchingEvent,bunchingEventId}'",
                "jsonb_set(request_payload, '{bunchingEvent,bunchingEventId}', 'null')",
                "jsonb_set(request_payload, '{bunchingEvent,directionId}', '\"1\"')",
                "request_payload #- '{bunchingEvent,directionId}'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE planning.recommendation SET request_payload = " + mutation))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE planning.recommendation SET response_payload = response_payload - 'adjustmentSeconds'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void changedRequestCannotReuseAnIdempotencyKey() throws Exception {
        submit(validRequest());
        String changed = validRequest().replace("A vehicle breakdown delayed leader SBS8027Y",
                "A different diagnosis for the same event");

        assertThat(submit(changed).httpStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation", Integer.class)).isEqualTo(1);
    }

    @Test
    void observationOnlyAndUnconfiguredRoutesCannotProduceRecommendations() throws Exception {
        assertThat(submit(validRequest().replace("\"SIMULATION\"", "\"OBSERVATION_ONLY\"")).httpStatus())
                .isEqualTo(422);
        String unsupportedRoute = validRequest().replace("\"190\"", "\"999\"")
                .replace("\"44251\"", "\"00000\"");
        assertThat(submit(unsupportedRoute).httpStatus()).isEqualTo(422);
    }

    @Test
    void aiGenerationIsDisabledByDefault() {
        assertThat(aiProperties.enabled()).isFalse();
        assertThat(geminiProperties.model()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(applicationContext.getBeansOfType(GeminiMitigationCandidateAdapter.class)).isEmpty();
    }

    private com.csd.occ.planning.diagnosis.domain.RecommendationSubmissionPort.DeliveryResult submit(String request) {
        return planningRecommendations.submit(UUID.fromString(IDEMPOTENCY_KEY), request);
    }

    private String validRequest() throws IOException {
        return new ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void insertAuditEvent(UUID id, Instant occurredAt, String category, String eventType, String outcome,
            String actorSubject, UUID runId, String entityId) {
        insertAuditEvent(id, occurredAt, category, eventType, outcome, actorSubject, runId, entityId, "{}");
    }

    private void insertAuditEvent(UUID id, Instant occurredAt, String category, String eventType, String outcome,
            String actorSubject, UUID runId, String entityId, String details) {
        jdbc.update("""
                INSERT INTO planning.application_audit_event
                    (id,occurred_at,recorded_at,category,event_type,outcome,actor_subject,actor_type,entity_type,entity_id,run_id,summary,details,source_key)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """, id, Timestamp.from(occurredAt), Timestamp.from(TEST_RECORDED_AT), category, eventType, outcome,
                actorSubject, "SYSTEM", "TEST_ENTITY", entityId, runId, "test event", details, "test:" + id);
    }

    private ModelInvocationRecord acceptedModelInvocation() {
        Instant startedAt = Instant.parse("2026-09-28T00:00:00Z");
        ModelInvocationMetadata metadata = new ModelInvocationMetadata(
                UUID.fromString("7778bb14-2fe4-4fcc-9149-642e1fd27c00"),
                ModelInvocationProvider.GEMINI,
                "gemini-3.1-flash-lite",
                "bunching-candidate-v1",
                "mitigation-candidate-v1",
                Optional.of("synthetic-response-id"),
                startedAt,
                startedAt.plusMillis(250),
                250,
                Optional.of(new ModelTokenUsage(Optional.of(120), Optional.of(25), Optional.of(145))));
        MitigationCandidate candidate = new MitigationCandidate(CandidateMitigationType.HOLD,
                "SBS5898U", "44241", 120, "A synthetic rationale for testing.");
        return new ModelInvocationRecord(UUID.fromString(IDEMPOTENCY_KEY), metadata,
                ModelInvocationOutcome.ACCEPTED, Optional.of(candidate), Optional.empty(), false, Optional.empty());
    }
}
