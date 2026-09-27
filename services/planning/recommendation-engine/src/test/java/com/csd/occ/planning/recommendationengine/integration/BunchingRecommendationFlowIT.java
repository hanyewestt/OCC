package com.csd.occ.planning.recommendationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.planning.recommendationengine.api.dto.CreateBunchingRecommendationRequest;
import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationCandidateValidator;
import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationContextPort;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationOrchestrator;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendationengine.infrastructure.config.RecommendationGeminiProperties;
import com.csd.occ.planning.recommendationengine.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import com.csd.occ.planning.recommendationengine.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendationengine.domain.DurableBunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidate;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateGenerationResult;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateGeneratorPort;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationMetadata;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationOutcome;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationProvider;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationRecord;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationRecordPort;
import com.csd.occ.planning.recommendationengine.domain.ModelTokenUsage;
import com.csd.occ.planning.recommendationengine.domain.RecommendationRecordPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BunchingRecommendationFlowIT {

    private static final String ENDPOINT = "/internal/v1/bunching-recommendations";
    private static final String IDEMPOTENCY_KEY = "f07c921a-bd3b-4a97-8b17-a384ef1269a7";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ")
            .withUsername("occ_admin")
            .withPassword("synthetic-admin")
            .withEnv("OCC_RECOMMENDATION_DB_PASSWORD", "synthetic-recommendation")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("local-init/04_recommendation_schema.sh"),
                    "/docker-entrypoint-initdb.d/04_recommendation_schema.sh");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_recommendation");
        registry.add("spring.datasource.password", () -> "synthetic-recommendation");
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecommendationAiProperties aiProperties;

    @Autowired
    private RecommendationGeminiProperties geminiProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ModelInvocationRecordPort modelInvocations;

    @Autowired
    private RecommendationRecordPort recommendationRecords;

    @Autowired
    private BunchingMitigationContextPort mitigationContext;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JsonMapper json;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clearRecommendationRecords() {
        jdbc.update("DELETE FROM recommendation.model_invocation_record");
        jdbc.update("DELETE FROM recommendation.recommendation_record");
    }

    @Test
    void aiCandidateGenerationIsDisabledByDefault() {
        assertThat(aiProperties.enabled()).isFalse();
        assertThat(geminiProperties.model()).isEqualTo("gemini-3.7-flash");
        assertThat(applicationContext.getBeansOfType(GeminiMitigationCandidateAdapter.class)).isEmpty();
    }

    @Test
    void fixtureBackedRequestReturnsExpectedRecommendation() throws IOException {
        var result = post(validRequest());

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.recommendationId").isEqualTo(IDEMPOTENCY_KEY);
        assertThat(result).bodyJson()
                .extractingPath("$.mitigationType").isEqualTo("HOLD");
        assertThat(result).bodyJson()
                .extractingPath("$.adjustmentSeconds").isEqualTo(120);
        assertThat(result).bodyJson()
                .extractingPath("$.targetBusReference").isEqualTo("SBS5898U");
        assertThat(result).bodyJson()
                .extractingPath("$.controlStopCode").isEqualTo("44241");
        assertThat(result).bodyJson()
                .extractingPath("$.expectedImpact.headwayAfterSeconds").isEqualTo(341);
        assertThat(result).bodyJson()
                .extractingPath("$.expectedImpact.queueAfterPassengers").isEqualTo(24);
        assertThat(result).bodyJson()
                .extractingPath("$.expectedImpact.passengerMinutesAfter").isEqualTo(128);
    }

    @Test
    void repeatedIdempotencyKeyReturnsIdenticalRecommendation() throws IOException {
        String firstResponse = post(validRequest()).getResponse().getContentAsString();
        String secondResponse = post(validRequest()).getResponse().getContentAsString();

        assertThat(secondResponse).isEqualTo(firstResponse);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.recommendation_record",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void recommendationPersistsAuditInputsContextAndOutput() throws IOException {
        post(validRequest());

        assertThat(jdbc.queryForMap("""
                SELECT request_id, bunching_event_id, diagnosis_id, mitigation_type,
                       recommendation_status, rationale, request_payload::text AS request_payload,
                       mitigation_context::text AS mitigation_context,
                       response_payload::text AS response_payload, created_at
                  FROM recommendation.recommendation_record
                 WHERE idempotency_key = ?::uuid
                """, IDEMPOTENCY_KEY))
                .containsEntry("mitigation_type", "HOLD")
                .containsEntry("recommendation_status", "RECOMMENDED")
                .satisfies(row -> {
                    assertThat(row.get("request_payload").toString()).contains("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");
                    assertThat(row.get("mitigation_context").toString()).contains("maximumHoldSeconds");
                    assertThat(row.get("response_payload").toString()).contains("route190-reference-directive-first-hold");
                    assertThat(row.get("created_at")).isNotNull();
                });
    }

    @Test
    void modelInvocationAuditPersistsSanitizedCandidateAndUsage() throws IOException {
        post(validRequest());
        modelInvocations.save(acceptedModelInvocation());

        assertThat(jdbc.queryForMap("""
                SELECT provider, model, prompt_version, output_schema_version,
                       provider_response_id, outcome, candidate_payload::text AS candidate_payload,
                       deterministic_fallback_used, prompt_tokens, completion_tokens,
                       total_tokens, latency_milliseconds, failure_category
                  FROM recommendation.model_invocation_record
                 WHERE recommendation_id = ?::uuid
                """, IDEMPOTENCY_KEY))
                .containsEntry("provider", "GEMINI")
                .containsEntry("model", "gemini-3.7-flash")
                .containsEntry("prompt_version", "bunching-candidate-v1")
                .containsEntry("output_schema_version", "mitigation-candidate-v1")
                .containsEntry("provider_response_id", "synthetic-response-id")
                .containsEntry("outcome", "ACCEPTED")
                .containsEntry("deterministic_fallback_used", false)
                .containsEntry("prompt_tokens", 120)
                .containsEntry("completion_tokens", 25)
                .containsEntry("total_tokens", 145)
                .containsEntry("latency_milliseconds", 250L)
                .containsEntry("failure_category", null)
                .satisfies(row -> assertThat(row.get("candidate_payload").toString())
                        .contains("SBS5898U", "44241", "requestedAdjustmentSeconds")
                        .doesNotContain("API", "key", "prompt"));
    }

    @Test
    void disabledAiStoresNoModelInvocationAudit() throws IOException {
        post(validRequest());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.model_invocation_record",
                Integer.class)).isZero();
    }

    @Test
    void enabledGenerationPersistsOneAuditAndReplayDoesNotInvokeProviderAgain() throws IOException {
        AtomicInteger providerCalls = new AtomicInteger();
        MitigationCandidateGeneratorPort generator = request -> {
            providerCalls.incrementAndGet();
            return new MitigationCandidateGenerationResult.Generated(modelMetadata(), matchingCandidate());
        };
        DurableBunchingRecommendationService durable = durableWith(generator);
        CreateBunchingRecommendationRequest request = json.readValue(
                validRequest(),
                CreateBunchingRecommendationRequest.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        for (int attempt = 0; attempt < 2; attempt++) {
            transaction.execute(status -> durable.recommend(
                    UUID.fromString(IDEMPOTENCY_KEY),
                    "b".repeat(64),
                    request.requestId(),
                    request.classification(),
                    request.bunchingEvent().toDomain(),
                    request.diagnosis().toDomain()));
        }

        assertThat(providerCalls).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.recommendation_record",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.model_invocation_record",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT outcome FROM recommendation.model_invocation_record",
                String.class)).isEqualTo("ACCEPTED");
    }

    @Test
    void failedAuditInsertRollsBackNewRecommendation() throws IOException {
        post(validRequest());
        modelInvocations.save(acceptedModelInvocation());
        UUID secondRecommendationId = UUID.fromString("9c46e6f7-8bf8-4cc4-b3d3-0bc0d189d52f");
        MitigationCandidateGeneratorPort generator = request ->
                new MitigationCandidateGenerationResult.Generated(modelMetadata(), matchingCandidate());
        DurableBunchingRecommendationService durable = durableWith(generator);
        CreateBunchingRecommendationRequest request = json.readValue(
                validRequest(),
                CreateBunchingRecommendationRequest.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> durable.recommend(
                secondRecommendationId,
                "c".repeat(64),
                request.requestId(),
                request.classification(),
                request.bunchingEvent().toDomain(),
                request.diagnosis().toDomain())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.recommendation_record WHERE recommendation_id = ?",
                Integer.class,
                secondRecommendationId)).isZero();
    }

    @Test
    void changedRequestCannotReuseIdempotencyKey() throws IOException {
        post(validRequest());
        String changedRequest = validRequest().replace(
                "A vehicle breakdown delayed leader SBS8027Y",
                "A different diagnosis for the same event");

        var result = post(changedRequest);

        assertThat(result).hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:occ:problem:idempotency-conflict");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recommendation.recommendation_record",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void observationOnlyRequestCannotProduceRecommendation() throws IOException {
        String request = validRequest().replace("\"SIMULATION\"", "\"OBSERVATION_ONLY\"");

        var result = post(request);

        assertThat(result).hasStatus(422)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void routeWithoutFixtureContextCannotProduceRecommendation() throws IOException {
        String request = validRequest()
                .replace("\"190\"", "\"999\"")
                .replace("\"44251\"", "\"00000\"");

        var result = post(request);

        assertThat(result).hasStatus(422)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void groupedOpenApiDocumentsInternalEndpoint() {
        var result = mvc.get()
                .uri("/v3/api-docs/internal")
                .exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyText()
                .contains("\"openapi\":\"3.1.0\"")
                .contains("/internal/v1/bunching-recommendations")
                .contains("createBunchingRecommendation")
                .contains("409");
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String request) {
        return mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange();
    }

    private String validRequest() throws IOException {
        return new ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private ModelInvocationRecord acceptedModelInvocation() {
        return new ModelInvocationRecord(
                UUID.fromString(IDEMPOTENCY_KEY),
                modelMetadata(),
                ModelInvocationOutcome.ACCEPTED,
                Optional.of(matchingCandidate()),
                Optional.empty(),
                false,
                Optional.empty());
    }

    private ModelInvocationMetadata modelMetadata() {
        Instant startedAt = Instant.parse("2026-09-28T00:00:00Z");
        return new ModelInvocationMetadata(
                UUID.fromString("7778bb14-2fe4-4fcc-9149-642e1fd27c00"),
                ModelInvocationProvider.GEMINI,
                "gemini-3.7-flash",
                "bunching-candidate-v1",
                "mitigation-candidate-v1",
                Optional.of("synthetic-response-id"),
                startedAt,
                startedAt.plusMillis(250),
                250,
                Optional.of(new ModelTokenUsage(
                        Optional.of(120),
                        Optional.of(25),
                        Optional.of(145))));
    }

    private MitigationCandidate matchingCandidate() {
        return new MitigationCandidate(
                CandidateMitigationType.HOLD,
                "SBS5898U",
                "44241",
                120,
                "Hold the following bus to restore the approved headway safely.");
    }

    private DurableBunchingRecommendationService durableWith(MitigationCandidateGeneratorPort generator) {
        BunchingRecommendationOrchestrator orchestrator = new BunchingRecommendationOrchestrator(
                true,
                Optional.of(generator),
                new BunchingMitigationCandidateValidator(),
                new BunchingRecommendationPolicy());
        BunchingRecommendationService recommendations = new BunchingRecommendationService(
                mitigationContext,
                orchestrator);
        return new DurableBunchingRecommendationService(
                recommendations,
                recommendationRecords,
                modelInvocations,
                clock);
    }
}
