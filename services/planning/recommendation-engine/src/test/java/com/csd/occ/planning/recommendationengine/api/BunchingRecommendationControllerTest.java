package com.csd.occ.planning.recommendationengine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendationengine.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendationengine.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendationengine.domain.FollowingBusState;
import com.csd.occ.planning.recommendationengine.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendationengine.domain.SimulatedImpactEstimate;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
@Import({GlobalExceptionHandler.class, BunchingRecommendationControllerTest.TestConfig.class})
class BunchingRecommendationControllerTest {

    private static final String ENDPOINT = "/internal/v1/bunching-recommendations";
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");

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
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
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

    private String validRequest() throws IOException {
        return new ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        BunchingRecommendationService bunchingRecommendationService() {
            return new BunchingRecommendationService(
                    event -> event.serviceId().equals("190")
                            ? Optional.of(inputs())
                            : Optional.empty(),
                    new BunchingRecommendationPolicy());
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
