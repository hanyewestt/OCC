package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RootCauseDiagnosisProcessorTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private final DiagnosisRequestRepository requests = mock(DiagnosisRequestRepository.class);
    private final EventFactsPort eventFacts = mock(EventFactsPort.class);
    private final ContextEvidencePort contextEvidence = mock(ContextEvidencePort.class);
    private final RootCauseModelPort model = mock(RootCauseModelPort.class);
    private final SnapshotCodecPort snapshots = mock(SnapshotCodecPort.class);
    private final RecommendationPayloadCodecPort payloads = mock(RecommendationPayloadCodecPort.class);
    private final DiagnosisCompletionService completion = mock(DiagnosisCompletionService.class);
    private final DiagnosisPolicySettings settings = new DiagnosisPolicySettings(Duration.ofMinutes(15), 2.0,
            0.70, 3, Duration.ofSeconds(2), Duration.ofMinutes(1));
    private final RootCauseDiagnosisProcessor processor = new RootCauseDiagnosisProcessor(requests, eventFacts,
            contextEvidence, model, snapshots, payloads, completion, settings,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID requestId = UUID.randomUUID();
    private final UUID demoRunId = UUID.randomUUID();
    private final UUID firstObservationId = UUID.randomUUID();
    private final UUID secondObservationId = UUID.randomUUID();
    private DiagnosisRequest request;
    private BunchingEventFacts event;
    private RootCauseContext context;

    @BeforeEach
    void setUp() {
        request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING, "disruption",
                UUID.randomUUID(), DiagnosisRequestService.SCHEMA_VERSION, NOW);
        request.claim(NOW.plus(Duration.ofMinutes(1)), NOW);
        event = new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", demoRunId,
                "190", 1, "44009", firstObservationId, secondObservationId, "CONFIRMED", 600,
                List.of(360, 390), NOW, List.of(NOW.minusSeconds(30), NOW.minusSeconds(10)),
                List.of(firstObservationId, secondObservationId), List.of("bunching-evidence-1"),
                "absolute-headway-v2");
        context = new RootCauseContext("SIMULATION", demoRunId, null,
                List.of(arrival(firstObservationId, "arrival-evidence-1"),
                        arrival(secondObservationId, "arrival-evidence-2")), List.of(), List.of());
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(eventFacts.findConfirmedEvent(request.eventId())).thenReturn(event);
        when(contextEvidence.findContext(event)).thenReturn(context);
        when(snapshots.encode(any(EvidenceSnapshot.class))).thenReturn("{\"snapshot\":true}");
        when(payloads.encode(any(UUID.class), eq(event), any(RootCauseDiagnosis.class), eq("SIMULATION")))
                .thenReturn("persisted-recommendation-payload");
    }

    @Test
    void validUnknownOutputIsCompletedWithCanonicalEvidenceAndPayload() {
        ModelInvocationDetails invocation = invocation(1);
        when(model.diagnose(any(EvidenceSnapshot.class), eq(List.of()), eq(1)))
                .thenReturn(new RootCauseModelPort.ModelCallResult(
                        new ModelOutput("UNKNOWN", null, null, event.evidenceIds(), List.of()), invocation));

        processor.process(requestId);

        ArgumentCaptor<RootCauseDiagnosis> diagnosis = ArgumentCaptor.forClass(RootCauseDiagnosis.class);
        ArgumentCaptor<List<ModelInvocationDetails>> invocations = ArgumentCaptor.forClass(List.class);
        verify(completion).complete(eq(requestId), diagnosis.capture(), invocations.capture(), anyString(),
                eq("persisted-recommendation-payload"), any(UUID.class));
        assertThat(diagnosis.getValue().causeCode()).isEqualTo("UNKNOWN");
        assertThat(diagnosis.getValue().confidence()).isNull();
        assertThat(diagnosis.getValue().evidenceSupportScore()).isNull();
        assertThat(diagnosis.getValue().confidenceComponents()).isNull();
        assertThat(diagnosis.getValue().supportingEvidenceIds()).containsExactlyElementsOf(event.evidenceIds());
        assertThat(invocations.getValue()).singleElement().extracting(ModelInvocationDetails::validationOutcome)
                .isEqualTo("VALID");
        verify(completion, never()).fail(any(UUID.class), anyString(), anyBoolean(), anyBoolean(),
                any(Instant.class), anyList(), any());
    }

    @Test
    void serverDerivedMissingEvidenceIsNotCrowdedOutByModelLimitations() {
        context = new RootCauseContext("SIMULATION", demoRunId, null, context.arrivals(), List.of(), List.of("bus-progress"));
        when(contextEvidence.findContext(event)).thenReturn(context);
        List<String> modelLimitations = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> "Model limitation " + index).toList();
        when(model.diagnose(any(EvidenceSnapshot.class), anyList(), eq(1))).thenReturn(
                new RootCauseModelPort.ModelCallResult(
                        new ModelOutput("UNKNOWN", null, null, event.evidenceIds(), modelLimitations), invocation(1)));

        processor.process(requestId);

        var diagnosis = ArgumentCaptor.forClass(RootCauseDiagnosis.class);
        verify(completion).complete(eq(requestId), diagnosis.capture(), anyList(), anyString(),
                eq("persisted-recommendation-payload"), any(UUID.class));
        assertThat(diagnosis.getValue().limitations()).contains("Missing evidence field: bus-progress");
    }

    @Test
    void deprecatedConfidenceThresholdCannotAlterEvidenceOrRecommendationDelivery() {
        var incident = new IncidentEvidence("incident-evidence-1", UUID.randomUUID(), "Vehicle Breakdown",
                "Provider-reported breakdown", BigDecimal.ONE, BigDecimal.ONE, NOW.minusSeconds(180),
                NOW.minusSeconds(60), "SIMULATION", demoRunId);
        context = new RootCauseContext("SIMULATION", demoRunId, null, context.arrivals(), List.of(incident), List.of());
        when(contextEvidence.findContext(event)).thenReturn(context);
        when(model.diagnose(any(EvidenceSnapshot.class), eq(List.of()), eq(1)))
                .thenReturn(new RootCauseModelPort.ModelCallResult(new ModelOutput("TRAFFIC_INCIDENT",
                        "Vehicle Breakdown", "Model explanation", List.of(incident.evidenceId()), List.of()), invocation(1)));

        for (double threshold : List.of(0.0, 1.0)) {
            var alternative = new DiagnosisPolicySettings(settings.evidenceLookback(), settings.evidenceRadiusKm(),
                    threshold, settings.maxAttempts(), settings.retryInitialDelay(), settings.retryMaxDelay());
            new RootCauseDiagnosisProcessor(requests, eventFacts, contextEvidence, model, snapshots, payloads,
                    completion, alternative, Clock.fixed(NOW, ZoneOffset.UTC)).process(requestId);
        }

        var diagnoses = ArgumentCaptor.forClass(RootCauseDiagnosis.class);
        verify(completion, org.mockito.Mockito.times(2)).complete(eq(requestId), diagnoses.capture(), anyList(),
                anyString(), eq("persisted-recommendation-payload"), any(UUID.class));
        assertThat(diagnoses.getAllValues().getFirst()).usingRecursiveComparison().ignoringFields("id")
                .isEqualTo(diagnoses.getAllValues().getLast());
        assertThat(diagnoses.getAllValues()).allSatisfy(diagnosis -> {
            assertThat(diagnosis.causeCode()).isEqualTo("TRAFFIC_INCIDENT");
            assertThat(diagnosis.confidence()).isNull();
            assertThat(diagnosis.evidenceSupportScore()).isNull();
            assertThat(diagnosis.confidenceComponents()).isNull();
            assertThat(diagnosis.likelyCause()).isEqualTo(
                    "Possible contributor: Vehicle Breakdown. The provider reported: Provider-reported breakdown.");
            assertThat(diagnosis.limitations()).noneMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains("threshold"));
        });
        verify(completion, never()).fail(any(UUID.class), anyString(), anyBoolean(), anyBoolean(),
                any(Instant.class), anyList(), any());
    }

    @Test
    void acceptsConfirmingArrivalsFetchedAfterTheFirstConfirmationTime() {
        Instant lastConfirmation = NOW.plusSeconds(10);
        event = new BunchingEventFacts(event.eventId(), event.bunchingEventId(), event.classification(), demoRunId,
                event.serviceId(), event.directionId(), event.monitoredStopCode(), firstObservationId,
                secondObservationId, event.status(), event.expectedHeadwaySeconds(), event.observedHeadwaySeconds(),
                NOW, List.of(NOW, lastConfirmation), List.of(firstObservationId, secondObservationId),
                event.evidenceIds(), event.detectionRuleVersion());
        context = new RootCauseContext("SIMULATION", demoRunId, null,
                List.of(arrivalAt(firstObservationId, "arrival-evidence-1", NOW),
                        arrivalAt(secondObservationId, "arrival-evidence-2", lastConfirmation)), List.of(), List.of());
        when(eventFacts.findConfirmedEvent(request.eventId())).thenReturn(event);
        when(contextEvidence.findContext(event)).thenReturn(context);
        when(payloads.encode(any(UUID.class), eq(event), any(RootCauseDiagnosis.class), eq("SIMULATION")))
                .thenReturn("persisted-recommendation-payload");
        when(model.diagnose(any(EvidenceSnapshot.class), eq(List.of()), eq(1)))
                .thenReturn(new RootCauseModelPort.ModelCallResult(
                        new ModelOutput("UNKNOWN", null, null, event.evidenceIds(), List.of()), invocation(1)));

        processor.process(requestId);

        verify(completion).complete(eq(requestId), any(RootCauseDiagnosis.class), anyList(), anyString(),
                eq("persisted-recommendation-payload"), any(UUID.class));
        verify(completion, never()).fail(any(UUID.class), anyString(), anyBoolean(), anyBoolean(),
                any(Instant.class), anyList(), any());
    }

    @Test
    void unsupportedModelCitationGetsOneRepairAttemptBeforeCompletion() {
        ModelInvocationDetails firstInvocation = invocation(1);
        ModelInvocationDetails repairedInvocation = invocation(2);
        when(model.diagnose(any(EvidenceSnapshot.class), anyList(), anyInt()))
                .thenReturn(new RootCauseModelPort.ModelCallResult(
                        new ModelOutput("UNKNOWN", null, null, List.of("not-allowed"), List.of()), firstInvocation))
                .thenReturn(new RootCauseModelPort.ModelCallResult(
                        new ModelOutput("UNKNOWN", null, null, event.evidenceIds(), List.of()), repairedInvocation));

        processor.process(requestId);

        ArgumentCaptor<List<ModelInvocationDetails>> invocations = ArgumentCaptor.forClass(List.class);
        verify(completion).complete(eq(requestId), any(RootCauseDiagnosis.class), invocations.capture(), anyString(),
                eq("persisted-recommendation-payload"), any(UUID.class));
        assertThat(invocations.getValue()).extracting(ModelInvocationDetails::validationOutcome)
                .containsExactly("INVALID", "VALID");
        verify(model, org.mockito.Mockito.times(2)).diagnose(any(EvidenceSnapshot.class), anyList(), anyInt());
    }

    @Test
    void evidenceLimitIsTerminalAndDoesNotCallTheModel() {
        when(eventFacts.findConfirmedEvent(request.eventId()))
                .thenThrow(new EvidenceLimitExceededException("evidenceIds"));

        processor.process(requestId);

        verify(completion).fail(eq(requestId), eq("EVIDENCE_LIMIT_EXCEEDED"), eq(false), eq(true),
                any(Instant.class), eq(List.of()), eq(null));
        verify(model, never()).diagnose(any(EvidenceSnapshot.class), anyList(), anyInt());
    }

    @Test
    void retryableProviderFailureRecordsInvocationAndSchedulesRetry() {
        ModelInvocationDetails invocation = invocation(1);
        when(model.diagnose(any(EvidenceSnapshot.class), eq(List.of()), eq(1)))
                .thenThrow(new ModelInvocationFailureException(true, invocation,
                        new IllegalStateException("synthetic provider failure")));

        processor.process(requestId);

        verify(completion).fail(eq(requestId), eq("MODEL_PROVIDER_FAILURE"), eq(true), eq(true),
                any(Instant.class), eq(List.of(invocation)), anyString());
    }

    @Test
    void liveEvidenceProducesAnAdvisoryWithoutUsingSimulationRecommendations() {
        event = new BunchingEventFacts(event.eventId(), event.bunchingEventId(), "OBSERVATION_ONLY", null,
                "190", 1, "44009", firstObservationId, secondObservationId, "CONFIRMED", 600,
                List.of(360, 390), NOW, event.confirmingObservationTimes(), event.confirmingObservationIds(),
                event.evidenceIds(), event.detectionRuleVersion());
        context = new RootCauseContext("OBSERVATION_ONLY", null, null,
                List.of(liveArrival(firstObservationId), liveArrival(secondObservationId)), List.of(), List.of());
        when(eventFacts.findConfirmedEvent(request.eventId())).thenReturn(event);
        when(contextEvidence.findContext(event)).thenReturn(context);
        when(model.diagnose(any(EvidenceSnapshot.class), anyList(), eq(1))).thenReturn(
                new RootCauseModelPort.ModelCallResult(new ModelOutput("UNKNOWN", null, null, event.evidenceIds(), List.of()), invocation(1)));

        processor.process(requestId);

        var result = ArgumentCaptor.forClass(RootCauseDiagnosis.class);
        verify(completion).completeAdvisory(eq(requestId), result.capture(), anyList(), anyString());
        assertThat(result.getValue().limitations()).anyMatch(value -> value.startsWith("Live diagnosis only:"));
        verify(payloads, never()).encode(any(), any(), any(), any());
        verify(completion, never()).complete(any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void liveEventCannotUseSimulationEvidence() {
        event = new BunchingEventFacts(event.eventId(), event.bunchingEventId(), "OBSERVATION_ONLY", null,
                "190", 1, "44009", firstObservationId, secondObservationId, "CONFIRMED", 600,
                List.of(360, 390), NOW, event.confirmingObservationTimes(), event.confirmingObservationIds(),
                event.evidenceIds(), event.detectionRuleVersion());
        when(eventFacts.findConfirmedEvent(request.eventId())).thenReturn(event);
        when(contextEvidence.findContext(event)).thenReturn(context);
        processor.process(requestId);
        verify(model, never()).diagnose(any(), anyList(), anyInt());
        verify(completion).fail(eq(requestId), eq("EVIDENCE_PROVENANCE_MISMATCH"), eq(false), eq(true), any(), anyList(), any());
    }

    private ArrivalEvidence liveArrival(UUID observationId) {
        return new ArrivalEvidence("bus-arrival:" + observationId, observationId, UUID.randomUUID(), UUID.randomUUID(),
                "190", "44009", 1, NOW.plusSeconds(60), NOW.minusSeconds(60),
                BigDecimal.ONE, BigDecimal.ONE, "LOW", "DD", "OBSERVATION_ONLY", null, null);
    }

    private ArrivalEvidence arrival(UUID observationId, String evidenceId) {
        return new ArrivalEvidence(evidenceId, observationId, UUID.randomUUID(), UUID.randomUUID(),
                "190", "44009", 1, NOW.plusSeconds(60), NOW.minusSeconds(60),
                BigDecimal.ONE, BigDecimal.ONE, "LOW", "DD", "SIMULATION", demoRunId, null);
    }

    private ArrivalEvidence arrivalAt(UUID observationId, String evidenceId, Instant fetchedAt) {
        return new ArrivalEvidence(evidenceId, observationId, UUID.randomUUID(), UUID.randomUUID(),
                "190", "44009", 1, fetchedAt, fetchedAt,
                BigDecimal.ONE, BigDecimal.ONE, "LOW", "DD", "SIMULATION", demoRunId, null);
    }

    private ModelInvocationDetails invocation(int attempt) {
        return new ModelInvocationDetails("GOOGLE_GENAI", "synthetic-model", "synthetic-response",
                "root-cause-prompt-v1", "root-cause-output-v1", NOW, NOW, 0, 1, 1, 2,
                "RESPONSE_RECEIVED", null, attempt);
    }
}
