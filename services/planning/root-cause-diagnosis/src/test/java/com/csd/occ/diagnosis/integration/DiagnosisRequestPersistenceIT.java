package com.csd.occ.diagnosis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.diagnosis.domain.EventType;
import com.csd.occ.diagnosis.domain.ConfidenceComponents;
import com.csd.occ.diagnosis.domain.DiagnosisCompletionService;
import com.csd.occ.diagnosis.domain.ModelInvocationDetails;
import com.csd.occ.diagnosis.domain.RecommendationDeliveryRepository;
import com.csd.occ.diagnosis.domain.RootCauseDiagnosis;
import com.csd.occ.diagnosis.domain.RootCauseDiagnosisRepository;
import com.csd.occ.diagnosis.infrastructure.jackson.JacksonSnapshotCodecAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "spring.flyway.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({DiagnosisRequestService.class, DiagnosisCompletionService.class, JacksonSnapshotCodecAdapter.class,
        DiagnosisRequestPersistenceIT.ClockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisRequestPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String PRODUCER = "bus-bunching-detection";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withInitScript("db/create-diagnosis-schema.sql");

    @Autowired DiagnosisRequestService requests;
    @Autowired DiagnosisRequestRepository repository;
    @Autowired RootCauseDiagnosisRepository diagnoses;
    @Autowired RecommendationDeliveryRepository deliveries;
    @Autowired DiagnosisCompletionService completion;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearDiagnosisTables() {
        jdbc.execute("TRUNCATE diagnosis.model_invocation_record, diagnosis.recommendation_delivery, "
                + "diagnosis.root_cause_diagnosis, diagnosis.diagnosis_request");
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalRequest() {
        UUID key = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        var original = requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, eventId, key);
        var replay = requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, eventId, key);

        assertThat(replay.id()).isEqualTo(original.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void businessEventUniquenessReturnsPreviouslyQueuedRequest() {
        UUID eventId = UUID.randomUUID();
        var original = requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, eventId, UUID.randomUUID());

        var duplicate = requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, eventId, UUID.randomUUID());

        assertThat(duplicate.id()).isEqualTo(original.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void reusedIdempotencyKeyWithDifferentEventIsRejected() {
        UUID key = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), key);

        assertThatThrownBy(() -> requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), key))
                .isInstanceOf(DiagnosisRequestService.IdempotencyConflictException.class);
    }

    @Test
    void expiredProcessingLeaseIsReclaimedAndAttemptCountAdvances() {
        UUID key = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), key);
        assertThat(requests.claimDueRequests(1, Duration.ofMinutes(1))).containsExactly(key);
        jdbc.update("update diagnosis.diagnosis_request set lease_until = ? where id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), key);

        assertThat(requests.claimDueRequests(1, Duration.ofMinutes(1))).containsExactly(key);
        assertThat(repository.findById(key).orElseThrow().attemptCount()).isEqualTo(2);
    }

    @Test
    void concurrentClaimersReceiveDisjointDueRequests() throws Exception {
        List<UUID> requestIds = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), UUID.randomUUID()).id())
                .toList();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var claim = (java.util.concurrent.Callable<List<UUID>>) () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claimers did not synchronize");
            return requests.claimDueRequests(10, Duration.ofMinutes(1));
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<UUID> claimed = java.util.stream.Stream.concat(first.get().stream(), second.get().stream()).toList();

            assertThat(claimed).hasSize(20).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(requestIds);
        }
    }

    @Test
    void completionAtomicallyPersistsDiagnosisInvocationAndDeliveryAndIgnoresReplay() {
        UUID requestId = UUID.randomUUID();
        var request = requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), requestId);
        assertThat(requests.claimDueRequests(1, Duration.ofMinutes(1))).containsExactly(requestId);
        RootCauseDiagnosis diagnosis = diagnosis(requestId, "synthetic-model");
        UUID deliveryId = UUID.randomUUID();

        completion.complete(requestId, diagnosis, List.of(invocation("synthetic-model")), "evidence-hash",
                "{\"recommendation\":true}", deliveryId);
        completion.complete(requestId, diagnosis, List.of(invocation("synthetic-model")), "evidence-hash",
                "{\"recommendation\":true}", UUID.randomUUID());

        assertThat(repository.findById(requestId).orElseThrow().status().name()).isEqualTo("COMPLETED");
        assertThat(diagnoses.findByRequestId(requestId)).isPresent();
        assertThat(deliveries.findById(deliveryId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.model_invocation_record WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.recommendation_delivery", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseConstraintFailureRollsBackDiagnosisInvocationDeliveryAndRequestTransition() {
        UUID requestId = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), requestId);
        requests.claimDueRequests(1, Duration.ofMinutes(1));
        RootCauseDiagnosis diagnosis = diagnosis(requestId, "synthetic-model");

        assertThatThrownBy(() -> completion.complete(requestId, diagnosis,
                List.of(invocation("x".repeat(101))), "evidence-hash", "{\"recommendation\":true}",
                UUID.randomUUID())).isInstanceOf(RuntimeException.class);

        assertThat(repository.findById(requestId).orElseThrow().status().name()).isEqualTo("PROCESSING");
        assertThat(diagnoses.findByRequestId(requestId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.model_invocation_record WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.recommendation_delivery", Integer.class))
                .isZero();
    }

    private RootCauseDiagnosis diagnosis(UUID requestId, String model) {
        return new RootCauseDiagnosis(UUID.randomUUID(), requestId, "UNKNOWN", null,
                "Available evidence is insufficient.", 0.0, 0.0, List.of("evidence-1"), List.of(),
                new ConfidenceComponents(1, 0, 0, 0, 0), "{}", "evidence-hash", "GOOGLE_GENAI", model,
                "prompt-v1", "schema-v1", NOW);
    }

    private ModelInvocationDetails invocation(String model) {
        return new ModelInvocationDetails("GOOGLE_GENAI", model, "synthetic-response", "prompt-v1", "schema-v1",
                NOW, NOW, 0, 1, 1, 2, "VALID", null, 1);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @org.springframework.context.annotation.Bean
        java.time.Clock diagnosisTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        JsonMapper diagnosisTestJsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
