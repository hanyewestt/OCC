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
import com.csd.occ.planning.recommendation.domain.MitigationCandidate;
import com.csd.occ.planning.recommendation.domain.ModelTokenUsage;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationGeminiProperties;
import com.csd.occ.planning.recommendation.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
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
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MockMvc mvc;
    @Autowired private PlanningRecommendationAdapter planningRecommendations;
    @Autowired private ModelInvocationRecordPort modelInvocations;
    @Autowired private RecommendationAiProperties aiProperties;
    @Autowired private RecommendationGeminiProperties geminiProperties;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext applicationContext;

    @BeforeEach
    void clearPlanningRecommendationRows() {
        jdbc.update("DELETE FROM planning.model_invocation_record");
        jdbc.update("DELETE FROM planning.recommendation");
    }

    @Test
    void exposesPlanningQueueMetricsOnlyToAdministrators() throws Exception {
        assertThat(meterRegistry.get("occ.planning.request.pending_count").gauge().value()).isEqualTo(0.0);
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.claim("occ_role", "ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("occ_planning_retention_deleted_rows_total")));
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
        assertThat(response.get("controlStopCode").asText()).isEqualTo("44241");
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
