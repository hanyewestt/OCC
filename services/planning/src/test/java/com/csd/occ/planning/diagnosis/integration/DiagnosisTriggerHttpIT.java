package com.csd.occ.planning.diagnosis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.csd.occ.planning.diagnosis.domain.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "OCC_PLANNING_DB_USERNAME=occ_planning", "OCC_PLANNING_DB_PASSWORD=synthetic-planning",
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1", "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED=false", "OCC_PLANNING_DIAGNOSIS_AUTO_PROCESS_BUNCHING=false",
        "OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false", "OCC_PLANNING_GEMINI_API_KEY=test-no-network"
})
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisTriggerHttpIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withInitScript("db/create-planning-schema.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
    }

    @Autowired Environment environment;
    @Autowired Clock clock;
    @Autowired DiagnosisRequestRepository repository;
    @Autowired DiagnosisRequestService requests;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RootCauseModelPort model;

    @Test
    void triggerReturnsQueuedStatusOverHttpAndDuplicateCannotTakeOverTheWorkerLease() throws Exception {
        var request = save(EventType.BUS_BUNCHING);
        assertThat(trigger(request.id())).contains("\"status\":\"PENDING\"");
        assertThat(repository.findById(request.id()).orElseThrow().isProcessingRequested()).isTrue();
        assertThat(requests.claimDueRequests(1, Duration.ofMinutes(5), false)).containsExactly(request.id());
        var claimed = repository.findById(request.id()).orElseThrow();

        assertThat(trigger(request.id())).contains("\"status\":\"PROCESSING\"");
        var afterReplay = repository.findById(request.id()).orElseThrow();
        assertThat(afterReplay.leaseUntil()).isEqualTo(claimed.leaseUntil());
        assertThat(afterReplay.attemptCount()).isEqualTo(1);
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = {"BUS_BUNCHING", "TRAFFIC_INCIDENT", "HAZARD"})
    void completedRequestsWithoutADiagnosisRemainCompletedOverHttp(EventType type) throws Exception {
        var request = save(type);
        String outcome = type == EventType.BUS_BUNCHING ? "LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT"
                : type == EventType.TRAFFIC_INCIDENT ? "TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION" : null;
        if (outcome == null) request.complete(clock.instant());
        else request.completeNoAction(outcome, clock.instant());
        repository.saveAndFlush(request);

        assertThat(trigger(request.id())).contains("\"status\":\"COMPLETED\"");
        var afterReplay = repository.findById(request.id()).orElseThrow();
        assertThat(afterReplay.status()).isEqualTo(DiagnosisRequestStatus.COMPLETED);
        assertThat(afterReplay.policyOutcome()).isEqualTo(outcome);
        verifyNoInteractions(model);
    }

    private DiagnosisRequest save(EventType type) {
        return repository.saveAndFlush(new DiagnosisRequest(UUID.randomUUID(), type, "disruption", UUID.randomUUID(),
                DiagnosisRequestService.SCHEMA_VERSION, clock.instant()));
    }

    private String trigger(UUID id) throws Exception {
        // Signature verification is mocked; the real HTTP security filter still enforces the controller role.
        when(decoder.decode("synthetic-controller")).thenReturn(Jwt.withTokenValue("synthetic-controller")
                .header("alg", "ES256").subject("controller").claim("occ_role", "CONTROLLER")
                .audience(List.of("authenticated")).build());
        var uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")
                + "/api/v1/planning/diagnoses/trigger");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer synthetic-controller").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"" + id + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }
}
