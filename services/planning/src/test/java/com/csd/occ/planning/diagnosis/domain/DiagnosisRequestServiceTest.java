package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisRequestServiceTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void acceptedSubmissionPersistsProducerFactsBeforeReturning() {
        DiagnosisRequestRepository requests = mock(DiagnosisRequestRepository.class);
        DiagnosisInputService inputs = mock(DiagnosisInputService.class);
        EventSubmissionFingerprintPort fingerprints = mock(EventSubmissionFingerprintPort.class);
        DiagnosisRequestPersistenceService persistence = mock(DiagnosisRequestPersistenceService.class);
        DiagnosisRequestService service = new DiagnosisRequestService(persistence, inputs, fingerprints);
        ProducerEventSubmission submission = submission();
        UUID key = UUID.randomUUID();
        DiagnosisRequest stored = new DiagnosisRequest(key, EventType.BUS_BUNCHING, "disruption",
                submission.eventId(), DiagnosisRequestService.SCHEMA_VERSION, NOW);
        when(fingerprints.fingerprint(submission)).thenReturn(FINGERPRINT);
        when(persistence.findById(key)).thenReturn(Optional.empty());
        when(persistence.findByEvent("BUS_BUNCHING", "disruption", submission.eventId(),
                DiagnosisRequestService.SCHEMA_VERSION)).thenReturn(Optional.empty());
        when(inputs.capture(submission)).thenReturn("immutable-input-snapshot");
        when(persistence.enqueueProducer(submission, key, "immutable-input-snapshot")).thenReturn(stored);

        DiagnosisRequest accepted = service.enqueue(submission, key, FINGERPRINT);

        assertThat(accepted).isSameAs(stored);
        assertThat(submission.detectedAt()).isAfter(submission.eventSnapshot().confirmingObservationTimes().getLast());
        verify(persistence).enqueueProducer(submission, key, "immutable-input-snapshot");
    }

    @Test
    void mismatchedSemanticFingerprintIsRejectedBeforeReadingOrCapturingFacts() {
        DiagnosisInputService inputs = mock(DiagnosisInputService.class);
        EventSubmissionFingerprintPort fingerprints = mock(EventSubmissionFingerprintPort.class);
        DiagnosisRequestPersistenceService persistence = mock(DiagnosisRequestPersistenceService.class);
        DiagnosisRequestService service = new DiagnosisRequestService(persistence, inputs, fingerprints);
        ProducerEventSubmission submission = submission();
        when(fingerprints.fingerprint(submission)).thenReturn(FINGERPRINT);

        assertThatThrownBy(() -> service.enqueue(submission, UUID.randomUUID(), "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(persistence, inputs);
    }

    @Test
    void detectionBeforeLastConfirmingObservationIsRejectedBeforePersistence() {
        DiagnosisInputService inputs = mock(DiagnosisInputService.class);
        EventSubmissionFingerprintPort fingerprints = mock(EventSubmissionFingerprintPort.class);
        DiagnosisRequestPersistenceService persistence = mock(DiagnosisRequestPersistenceService.class);
        DiagnosisRequestService service = new DiagnosisRequestService(persistence, inputs, fingerprints);
        ProducerEventSubmission valid = submission();
        ProducerEventSubmission stale = new ProducerEventSubmission(valid.schemaVersion(), valid.eventType(),
                valid.producer(), valid.eventId(), valid.disruptionId(), valid.classification(), valid.demoRunId(),
                NOW.minusMillis(1), valid.detectionRuleVersion(), valid.evidenceIds(), valid.eventSnapshot());
        when(fingerprints.fingerprint(stale)).thenReturn(FINGERPRINT);

        assertThatThrownBy(() -> service.enqueue(stale, UUID.randomUUID(), FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(persistence, inputs);
    }

    @Test
    void replayWithChangedProducerFactsConflictsWithoutReplacingTheOriginalSnapshot() {
        DiagnosisInputService inputs = mock(DiagnosisInputService.class);
        EventSubmissionFingerprintPort fingerprints = mock(EventSubmissionFingerprintPort.class);
        DiagnosisRequestPersistenceService persistence = mock(DiagnosisRequestPersistenceService.class);
        DiagnosisRequestService service = new DiagnosisRequestService(persistence, inputs, fingerprints);
        ProducerEventSubmission submission = submission();
        UUID key = UUID.randomUUID();
        DiagnosisRequest stored = new DiagnosisRequest(key, EventType.BUS_BUNCHING, "disruption",
                submission.eventId(), DiagnosisRequestService.SCHEMA_VERSION, NOW);
        stored.retainInput("original-snapshot");
        when(fingerprints.fingerprint(submission)).thenReturn(FINGERPRINT);
        when(persistence.findById(key)).thenReturn(Optional.of(stored));
        when(inputs.matchesProducerFacts("original-snapshot", submission)).thenReturn(false);

        assertThatThrownBy(() -> service.enqueue(submission, key, FINGERPRINT))
                .isInstanceOf(DiagnosisRequestService.IdempotencyConflictException.class);

        verify(inputs, never()).capture(submission);
        assertThat(stored.inputSnapshot()).isEqualTo("original-snapshot");
    }

    @Test
    void trafficIncidentSubmissionIsTypedAndCapturedWithoutBunchingContext() {
        DiagnosisInputService inputs = mock(DiagnosisInputService.class);
        EventSubmissionFingerprintPort fingerprints = mock(EventSubmissionFingerprintPort.class);
        DiagnosisRequestPersistenceService persistence = mock(DiagnosisRequestPersistenceService.class);
        DiagnosisRequestService service = new DiagnosisRequestService(persistence, inputs, fingerprints);
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        Instant at = NOW;
        TrafficIncidentEventFacts traffic = new TrafficIncidentEventFacts(eventId, disruptionId, "OPENED",
                "OBSERVATION_ONLY", null, observationId, UUID.randomUUID(), "LTA", "a".repeat(64),
                "Road Obstruction", "Westbound lane blocked", new java.math.BigDecimal("1.38"),
                new java.math.BigDecimal("103.75"), at.minusSeconds(1), at, at);
        ProducerEventSubmission submission = new ProducerEventSubmission(DiagnosisRequestService.SCHEMA_VERSION,
                EventType.TRAFFIC_INCIDENT, "disruption", eventId, disruptionId, "OBSERVATION_ONLY", null, at,
                "traffic-incident-observation-v1", List.of(observationId.toString()), null, traffic);
        UUID key = UUID.randomUUID();
        DiagnosisRequest stored = new DiagnosisRequest(key, EventType.TRAFFIC_INCIDENT, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, NOW);
        when(fingerprints.fingerprint(submission)).thenReturn(FINGERPRINT);
        when(persistence.findById(key)).thenReturn(Optional.empty());
        when(persistence.findByEvent("TRAFFIC_INCIDENT", "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION)).thenReturn(Optional.empty());
        when(inputs.capture(submission)).thenReturn("immutable-traffic-input");
        when(persistence.enqueueProducer(submission, key, "immutable-traffic-input")).thenReturn(stored);

        DiagnosisRequest accepted = service.enqueue(submission, key, FINGERPRINT);

        assertThat(accepted).isSameAs(stored);
        verify(inputs).capture(submission);
        verify(persistence).enqueueProducer(submission, key, "immutable-traffic-input");
    }

    private ProducerEventSubmission submission() {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        List<UUID> observations = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<String> evidenceIds = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("bunching-alert-event:" + eventId),
                observations.stream().map(id -> "bus-arrival:" + id)).toList();
        Instant first = NOW.minusSeconds(300);
        BunchingEventFacts facts = new BunchingEventFacts(eventId, disruptionId, "SIMULATION", demoRunId, "190", 1,
                "44009", observations.get(2), observations.get(3), "CONFIRMED", 600, List.of(360, 390), first,
                List.of(first, NOW), observations, evidenceIds, "bunching-gap-v1");
        return new ProducerEventSubmission("planning-event-v1", EventType.BUS_BUNCHING, "disruption", eventId,
                disruptionId, "SIMULATION", demoRunId, NOW.plusMillis(1), "bunching-gap-v1", evidenceIds, facts);
    }
}
