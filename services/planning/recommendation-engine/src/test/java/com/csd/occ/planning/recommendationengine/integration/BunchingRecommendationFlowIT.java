package com.csd.occ.planning.recommendationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendationengine.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendationengine.infrastructure.config.RecommendationGeminiProperties;
import com.csd.occ.planning.recommendationengine.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

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

    @BeforeEach
    void clearRecommendationRecords() {
        jdbc.update("DELETE FROM recommendation.recommendation_record");
    }

    @Test
    void aiCandidateGenerationIsDisabledByDefault() {
        assertThat(aiProperties.enabled()).isFalse();
        assertThat(geminiProperties.model()).isEqualTo("gemini-2.5-flash");
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
}
