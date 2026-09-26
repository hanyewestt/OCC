package com.csd.occ.planning.recommendationengine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendationengine.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationCandidateValidator;
import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationOrchestrator;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendationengine.domain.FollowingBusState;
import com.csd.occ.planning.recommendationengine.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationRecordPort;
import com.csd.occ.planning.recommendationengine.domain.SimulatedImpactEstimate;
import com.csd.occ.planning.recommendationengine.domain.DurableBunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.domain.RecommendationRecord;
import com.csd.occ.planning.recommendationengine.domain.RecommendationRecordPort;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationContext;
import java.time.Clock;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(BunchingRecommendationController.class)
@Import({
        GlobalExceptionHandler.class,
        RecommendationRequestFingerprint.class,
        BunchingRecommendationControllerTest.TestConfig.class
})
class BunchingRecommendationControllerTest {

    private static final String ENDPOINT = "/internal/v1/bunching-recommendations";
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID UNAVAILABLE_ACTION_KEY =
            UUID.fromString("7c6328d6-d41e-4bb7-acb3-cce1495b6ba1");
    private static final UUID CONFLICT_KEY =
            UUID.fromString("3a2f1792-44c6-4b14-8db6-f82361c13fd4");

    @Autowired
    private MockMvcTester mvc;

    @Test
    void validRequestReturnsOneRecommendation() throws IOException {
        var result = mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest())
                .exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.mitigationType").isEqualTo("HOLD");
        assertThat(result).bodyJson()
                .extractingPath("$.explanation.tradeOffs[0]")
                .asString().contains("120 seconds");
    }

    @Test
    void invalidNestedFieldsReturnProblemDetail() {
        String invalidRequest = """
                {
                  "requestId": "6e2f6b21-9c43-4c17-93c5-10b2d820b9a7",
                  "classification": "SIMULATION",
                  "bunchingEvent": {
                    "directionId": 1,
                    "expectedHeadwaySeconds": 600,
                    "observedHeadwaySeconds": [276, 221],
                    "evidenceIds": []
                  },
                  "diagnosis": {
                    "confidence": 0.5,
                    "evidenceIds": [],
                    "limitations": []
                  }
                }
                """;

        var result = mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest)
                .exchange();

        assertThat(result).hasStatus(400)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.title").isEqualTo("Request validation failed");
        assertThat(result).bodyJson()
                .extractingPath("$.errors").asArray().isNotEmpty();
    }

    @Test
    void unavailableActionReturnsUnprocessableProblemDetail() throws IOException {
        String request = validRequest()
                .replace("\"190\"", "\"999\"")
                .replace("\"44251\"", "\"00000\"");

        var result = mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", UNAVAILABLE_ACTION_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange();

        assertThat(result).hasStatus(422)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:occ:problem:no-actionable-recommendation");
    }

    @Test
    void missingIdempotencyKeyReturnsProblemDetail() throws IOException {
        var result = mvc.post()
                .uri(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest())
                .exchange();

        assertThat(result).hasStatus(400)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void reusedIdempotencyKeyWithDifferentRequestReturnsConflict() throws IOException {
        mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", CONFLICT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest())
                .exchange();

        String changedRequest = validRequest().replace(
                "A vehicle breakdown delayed leader SBS8027Y",
                "A different diagnosis for the same event");
        var result = mvc.post()
                .uri(ENDPOINT)
                .header("Idempotency-Key", CONFLICT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(changedRequest)
                .exchange();

        assertThat(result).hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:occ:problem:idempotency-conflict");
    }

    private String validRequest() throws IOException {
        return new ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        BunchingRecommendationService bunchingRecommendationService() {
            BunchingRecommendationPolicy policy = new BunchingRecommendationPolicy();
            return new BunchingRecommendationService(
                    event -> event.serviceId().equals("190")
                            ? Optional.of(inputs())
                            : Optional.empty(),
                    new BunchingRecommendationOrchestrator(
                            false,
                            Optional.empty(),
                            new BunchingMitigationCandidateValidator(),
                            policy));
        }

        @Bean
        RecommendationRecordPort recommendationRecordPort() {
            return new RecommendationRecordPort() {
                private final ConcurrentHashMap<UUID, RecommendationRecord> records = new ConcurrentHashMap<>();

                @Override
                public Optional<RecommendationRecord> findByIdempotencyKey(UUID idempotencyKey) {
                    return Optional.ofNullable(records.get(idempotencyKey));
                }

                @Override
                public boolean saveIfAbsent(RecommendationRecord record) {
                    return records.putIfAbsent(record.idempotencyKey(), record) == null;
                }
            };
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ModelInvocationRecordPort modelInvocationRecordPort() {
            return record -> { };
        }

        @Bean
        DurableBunchingRecommendationService durableBunchingRecommendationService(
                BunchingRecommendationService recommendations,
                RecommendationRecordPort records,
                ModelInvocationRecordPort modelInvocations,
                Clock clock) {
            return new DurableBunchingRecommendationService(recommendations, records, modelInvocations, clock);
        }

        private BunchingMitigationInputs inputs() {
            return new BunchingMitigationInputs(
                    new ValidatedMitigationContext(
                            "SBS5898U",
                            "44241",
                            FollowingBusState.READY_TO_DEPART,
                            120,
                            75,
                            CrowdSafetyStatus.PASSED,
                            MitigationValidationStatus.VALIDATED,
                            List.of("route190-reference-directive-first-hold")),
                    new SimulatedImpactEstimate(
                            221,
                            341,
                            38,
                            24,
                            new BigDecimal("190"),
                            new BigDecimal("128")));
        }
    }
}
