package com.csd.occ.planning.diagnosis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestPersistenceService;
import com.csd.occ.planning.diagnosis.domain.EventType;
import com.csd.occ.planning.diagnosis.domain.BunchingEventFacts;
import com.csd.occ.planning.diagnosis.domain.EvidenceSnapshot;
import com.csd.occ.planning.diagnosis.domain.EventSubmissionFingerprintPort;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import com.csd.occ.planning.diagnosis.domain.TrafficIncidentEventFacts;
import com.csd.occ.planning.diagnosis.domain.RootCauseContext;
import com.csd.occ.planning.diagnosis.domain.SnapshotCodecPort;
import com.csd.occ.planning.diagnosis.domain.ConfidenceComponents;
import com.csd.occ.planning.diagnosis.domain.DiagnosisCompletionService;
import com.csd.occ.planning.diagnosis.domain.ModelInvocationDetails;
import com.csd.occ.planning.diagnosis.domain.RecommendationDeliveryRepository;
import com.csd.occ.planning.diagnosis.domain.RootCauseDiagnosis;
import com.csd.occ.planning.diagnosis.domain.RootCauseDiagnosisRepository;
import com.csd.occ.planning.diagnosis.infrastructure.jackson.JacksonSnapshotCodecAdapter;
import com.csd.occ.planning.diagnosis.infrastructure.jackson.JacksonEventSubmissionFingerprintAdapter;
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
@Import({DiagnosisRequestService.class, DiagnosisRequestPersistenceService.class, DiagnosisCompletionService.class,
        JacksonSnapshotCodecAdapter.class, JacksonEventSubmissionFingerprintAdapter.class,
        DiagnosisRequestPersistenceIT.ClockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class DiagnosisRequestPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String PRODUCER = "disruption";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withInitScript("db/create-planning-schema.sql");

    @Autowired DiagnosisRequestService requests;
    @Autowired DiagnosisRequestRepository repository;
    @Autowired RootCauseDiagnosisRepository diagnoses;
    @Autowired RecommendationDeliveryRepository deliveries;
    @Autowired DiagnosisCompletionService completion;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventSubmissionFingerprintPort fingerprints;
    @Autowired SnapshotCodecPort snapshots;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.csd.occ.planning.diagnosis.domain.DiagnosisInputService inputs;

    @BeforeEach
    void clearDiagnosisTables() {
        jdbc.execute("TRUNCATE planning.model_invocation_record, planning.recommendation_job, "
                + "planning.diagnosis_result, planning.planning_request");
    }

    @Test
    void scopedRequestsKeepLiveAndEachSimulationRunSeparate() {
        UUID run = UUID.randomUUID();
        for (String scope : List.of("", run.toString(), UUID.randomUUID().toString())) {
            var request = new com.csd.occ.planning.diagnosis.domain.DiagnosisRequest(UUID.randomUUID(),
                    EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), DiagnosisRequestService.SCHEMA_VERSION, NOW);
            String classification = scope.isEmpty() ? "OBSERVATION_ONLY" : "SIMULATION";
            request.retainInput("{\"event\":{\"classification\":\"" + classification + "\",\"demoRunId\":"
                    + (scope.isEmpty() ? "null" : "\"" + scope + "\"") + "}}");
            repository.saveAndFlush(request);
        }
        var page = org.springframework.data.domain.PageRequest.of(0, 10);
        assertThat(repository.findScoped("OBSERVATION_ONLY", "", page).getTotalElements()).isEqualTo(1);
        assertThat(repository.findScoped("SIMULATION", run.toString(), page).getTotalElements()).isEqualTo(1);
        assertThat(repository.findScoped("SIMULATION", UUID.randomUUID().toString(), page).getTotalElements()).isZero();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
        registry.add("OCC_PLANNING_DB_USERNAME", () -> "occ_planning");
        registry.add("OCC_PLANNING_DB_PASSWORD", () -> "synthetic-planning");
        registry.add("SUPABASE_PROJECT_URL", () -> "https://example.supabase.co");
        registry.add("SUPABASE_JWKS_URL", () -> "https://example.supabase.co/auth/v1/.well-known/jwks.json");
        registry.add("OCC_PLANNING_DISRUPTION_PEER_TOKEN", () -> "synthetic-peer-token");
        registry.add("OCC_DISRUPTION_INTERNAL_URL", () -> "http://127.0.0.1:1");
        registry.add("OCC_INGESTION_INTERNAL_URL", () -> "http://127.0.0.1:1");
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
    void producerSnapshotIsPersistedBeforeAcceptanceAndChangedReplayConflicts() {
        UUID key = UUID.randomUUID();
        ProducerEventSubmission submission = producerSubmission(390);
        String inputSnapshot = snapshots.encode(new EvidenceSnapshot(submission.eventSnapshot(), context(submission),
                submission.evidenceIds(), submission.classification()));
        org.mockito.Mockito.when(inputs.capture(submission)).thenReturn(inputSnapshot);
        org.mockito.Mockito.when(inputs.matchesProducerFacts(inputSnapshot, submission)).thenReturn(true);
        String fingerprint = fingerprints.fingerprint(submission);

        var original = requests.enqueue(submission, key, fingerprint);
        var replay = requests.enqueue(submission, key, fingerprint);

        assertThat(replay.id()).isEqualTo(original.id());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(key).orElseThrow().inputSnapshot()).isEqualTo(inputSnapshot);

        ProducerEventSubmission changed = producerSubmission(submission, 391);
        org.mockito.Mockito.when(inputs.matchesProducerFacts(inputSnapshot, changed)).thenReturn(false);
        assertThatThrownBy(() -> requests.enqueue(changed, key, fingerprints.fingerprint(changed)))
                .isInstanceOf(DiagnosisRequestService.IdempotencyConflictException.class);
        assertThat(repository.findById(key).orElseThrow().inputSnapshot()).isEqualTo(inputSnapshot);
    }

    @Test
    void trafficIncidentIsQueuedWithImmutableEvidenceAndStableReplay() {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Instant eventAt = NOW.minusSeconds(2);
        var facts = new TrafficIncidentEventFacts(eventId, disruptionId, "OPENED", "OBSERVATION_ONLY", null,
                observationId, UUID.randomUUID(), "LTA", "c".repeat(64), "Road Obstruction",
                "Westbound lane blocked", new java.math.BigDecimal("1.38"), new java.math.BigDecimal("103.75"),
                eventAt.minusSeconds(1), eventAt, eventAt);
        var submission = new ProducerEventSubmission(DiagnosisRequestService.SCHEMA_VERSION,
                EventType.TRAFFIC_INCIDENT, PRODUCER, eventId, disruptionId, "OBSERVATION_ONLY", null, eventAt,
                "traffic-incident-observation-v1", List.of(observationId.toString()), null, facts);
        String inputSnapshot = snapshots.encodeProducerEvent(submission);
        org.mockito.Mockito.when(inputs.capture(submission)).thenReturn(inputSnapshot);
        org.mockito.Mockito.when(inputs.matchesProducerFacts(inputSnapshot, submission)).thenReturn(true);

        var accepted = requests.enqueue(submission, key, fingerprints.fingerprint(submission));
        var replay = requests.enqueue(submission, key, fingerprints.fingerprint(submission));

        var stored = repository.findById(key).orElseThrow();
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(stored.status().name()).isEqualTo("PENDING");
        assertThat(stored.policyOutcome()).isNull();
        assertThat(stored.inputSnapshot()).isEqualTo(inputSnapshot);
        assertThat(diagnoses.count()).isZero();
        assertThat(deliveries.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_job", Integer.class)).isZero();
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
        jdbc.update("update planning.planning_request set lease_until = ? where id = ?",
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
        assertThat(diagnoses.findByRequestId(requestId)).hasValueSatisfying(persisted ->
                assertThat(persisted.id()).isEqualTo(diagnosis.id()));
        assertThat(deliveries.findById(deliveryId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_job", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void liveAdvisoryCompletionPersistsDiagnosisAndInvocationWithoutDeliveryAndIgnoresReplay() {
        UUID requestId = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), requestId);
        assertThat(requests.claimDueRequests(1, Duration.ofMinutes(1))).containsExactly(requestId);
        RootCauseDiagnosis diagnosis = new RootCauseDiagnosis(UUID.randomUUID(), requestId, "UNKNOWN", null,
                "Available evidence is insufficient.", 0.0, 0.0, List.of("evidence-1"), List.of(),
                new ConfidenceComponents(1, 0, 0, 0, 0),
                "{\"event\":{\"classification\":\"OBSERVATION_ONLY\",\"demoRunId\":null}}",
                "evidence-hash", "GOOGLE_GENAI", "synthetic-model", "prompt-v1", "schema-v1", NOW);

        completion.completeAdvisory(requestId, diagnosis, List.of(invocation("synthetic-model")), "evidence-hash");
        completion.completeAdvisory(requestId, diagnosis, List.of(invocation("synthetic-model")), "evidence-hash");

        var stored = repository.findById(requestId).orElseThrow();
        assertThat(stored.status().name()).isEqualTo("COMPLETED");
        assertThat(stored.policyOutcome()).isEqualTo("LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT");
        assertThat(stored.failureCode()).isNull();
        assertThat(diagnoses.findByRequestId(requestId)).hasValueSatisfying(persisted ->
                assertThat(persisted.id()).isEqualTo(diagnosis.id()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(deliveries.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_job", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE planning.planning_request SET policy_outcome = ? WHERE id = ?",
                "UNSUPPORTED_OUTCOME", requestId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findById(requestId).orElseThrow().policyOutcome())
                .isEqualTo("LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT");
    }

    @Test
    void databaseConstraintFailureRollsBackDiagnosisInvocationDeliveryAndRequestTransition() {
        UUID requestId = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), requestId);
        requests.claimDueRequests(1, Duration.ofMinutes(1));
        RootCauseDiagnosis diagnosis = diagnosis(requestId, "synthetic-model");

        assertThatThrownBy(() -> completion.complete(requestId, diagnosis,
                List.of(invocation("x".repeat(33), "synthetic-model")), "evidence-hash", "{\"recommendation\":true}",
                UUID.randomUUID())).isInstanceOf(RuntimeException.class);

        assertThat(repository.findById(requestId).orElseThrow().status().name()).isEqualTo("PROCESSING");
        assertThat(diagnoses.findByRequestId(requestId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record WHERE request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_job", Integer.class))
                .isZero();
    }

    private RootCauseDiagnosis diagnosis(UUID requestId, String model) {
        return new RootCauseDiagnosis(UUID.randomUUID(), requestId, "UNKNOWN", null,
                "Available evidence is insufficient.", 0.0, 0.0, List.of("evidence-1"), List.of(),
                new ConfidenceComponents(1, 0, 0, 0, 0), "{}", "evidence-hash", "GOOGLE_GENAI", model,
                "prompt-v1", "schema-v1", NOW);
    }

    private ProducerEventSubmission producerSubmission(int secondHeadway) {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        List<UUID> observations = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<String> evidenceIds = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("bunching-alert-event:" + eventId),
                observations.stream().map(id -> "bus-arrival:" + id)).toList();
        Instant first = NOW.minusSeconds(300);
        BunchingEventFacts facts = new BunchingEventFacts(eventId, disruptionId, "SIMULATION", demoRunId, "190", 1,
                "44009", observations.get(2), observations.get(3), "CONFIRMED", 600, List.of(360, secondHeadway),
                first, List.of(first, NOW), observations, evidenceIds, "bunching-gap-v1");
        return new ProducerEventSubmission(DiagnosisRequestService.SCHEMA_VERSION, EventType.BUS_BUNCHING, PRODUCER,
                eventId, disruptionId, "SIMULATION", demoRunId, NOW, "bunching-gap-v1", evidenceIds, facts);
    }

    private ProducerEventSubmission producerSubmission(ProducerEventSubmission original, int secondHeadway) {
        BunchingEventFacts previous = original.eventSnapshot();
        BunchingEventFacts changedFacts = new BunchingEventFacts(previous.eventId(), previous.bunchingEventId(),
                previous.classification(), previous.demoRunId(), previous.serviceId(), previous.directionId(),
                previous.monitoredStopCode(), previous.leadingObservationId(), previous.followingObservationId(),
                previous.status(), previous.expectedHeadwaySeconds(), List.of(360, secondHeadway),
                previous.firstConfirmedAt(), previous.confirmingObservationTimes(),
                previous.confirmingObservationIds(), previous.evidenceIds(), previous.detectionRuleVersion());
        return new ProducerEventSubmission(original.schemaVersion(), original.eventType(), original.producer(),
                original.eventId(), original.disruptionId(), original.classification(), original.demoRunId(),
                original.detectedAt(), original.detectionRuleVersion(), original.evidenceIds(), changedFacts);
    }

    private RootCauseContext context(ProducerEventSubmission submission) {
        Instant at = submission.eventSnapshot().firstConfirmedAt().minusSeconds(2);
        List<com.csd.occ.planning.diagnosis.domain.ArrivalEvidence> arrivals =
                submission.eventSnapshot().confirmingObservationIds().stream().map(id ->
                        new com.csd.occ.planning.diagnosis.domain.ArrivalEvidence("bus-arrival:" + id, id,
                                UUID.randomUUID(), UUID.randomUUID(), "190", "44009", 1, at, at,
                                null, null, "LOW", "BUS", "SIMULATION", submission.demoRunId(), null)).toList();
        return new RootCauseContext("SIMULATION", submission.demoRunId(), null, arrivals, List.of(), List.of());
    }

    @Test void failedAttemptRetainsInputAndActualResponseWithoutAnEvidenceHashOrResult() {
        UUID requestId = UUID.randomUUID();
        requests.enqueue(EventType.BUS_BUNCHING, PRODUCER, UUID.randomUUID(), requestId);
        requests.claimDueRequests(1, Duration.ofMinutes(1));
        var details = new ModelInvocationDetails("GOOGLE_GENAI", "synthetic-model", "response-1", "prompt-v1", "schema-v1",
                NOW, NOW, 0, 1, 1, 2, "INVALID", null, 1, "{\"evidence\":true}", "{invalid-json");
        completion.fail(requestId, "MODEL_OUTPUT_INVALID", false, false, NOW, List.of(details), null);
        assertThat(jdbc.queryForMap("select input_snapshot, generated_response, evidence_snapshot_hash from planning.model_invocation_record"))
                .containsEntry("input_snapshot", "{\"evidence\":true}")
                .containsEntry("generated_response", "{invalid-json")
                .containsEntry("evidence_snapshot_hash", null);
        assertThat(diagnoses.findByRequestId(requestId)).isEmpty();
    }

    private ModelInvocationDetails invocation(String model) {
        return invocation("GOOGLE_GENAI", model);
    }

    private ModelInvocationDetails invocation(String provider, String model) {
        return new ModelInvocationDetails(provider, model, "synthetic-response", "prompt-v1", "schema-v1",
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
