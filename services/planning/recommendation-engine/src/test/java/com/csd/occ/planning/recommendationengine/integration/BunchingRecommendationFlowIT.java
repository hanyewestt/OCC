package com.csd.occ.planning.recommendationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
class BunchingRecommendationFlowIT {

    private static final String ENDPOINT = "/internal/v1/bunching-recommendations";
    private static final String IDEMPOTENCY_KEY = "f07c921a-bd3b-4a97-8b17-a384ef1269a7";

    @Autowired
    private MockMvcTester mvc;

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
                .contains("createBunchingRecommendation");
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
