package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;

class PlanningScopeServiceTest {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CONFIRMED = Instant.parse("2026-10-25T07:05:31Z");

    @Test
    void listsLatestHazardWorkAndSafeFailureCodesForTheSelectedRun() {
        var scopes = mock(PlanningScopePort.class);
        var requests = mock(DiagnosisRequestRepository.class);
        var codec = mock(SnapshotCodecPort.class);
        var service = new PlanningScopeService(scopes, requests, mock(RootCauseDiagnosisRepository.class), codec);
        UUID runId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-25T04:00:00Z");
        var request = new DiagnosisRequest(requestId, EventType.HAZARD, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, createdAt);
        request.failRetryable("HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE", createdAt.plusSeconds(30), createdAt);
        request.retainInput("retained-producer-event");
        var submission = new ProducerEventSubmission(DiagnosisRequestService.SCHEMA_VERSION, EventType.HAZARD,
                "disruption", eventId, disruptionId, "SIMULATION", runId, createdAt,
                "hazard-observation-v1", List.of("observation"), null, null,
                new com.csd.occ.planning.advisory.domain.HazardEventFacts(eventId, disruptionId, "OPENED", null,
                        "SIMULATION", runId, UUID.randomUUID(), 1L, UUID.randomUUID(), null, createdAt, null, null));
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", runId));
        when(requests.findOpenHazardRequests("SIMULATION", runId.toString(), PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 10), 1));
        when(codec.decodeProducerEvent("retained-producer-event")).thenReturn(submission);

        var page = service.listHazardRequests(0, 10);

        assertThat(page.mode()).isEqualTo("DEMO");
        assertThat(page.demoRunId()).isEqualTo(runId);
        assertThat(page.items()).containsExactly(new PlanningHazardRequestSummary(requestId, eventId, disruptionId,
                "HAZARD", DiagnosisRequestStatus.FAILED_RETRYABLE,
                "HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE", createdAt));
    }

    @Test
    void switchingModesOrSimulationRunsMakesTheOldDiagnosisIneligible() {
        var scopes = mock(PlanningScopePort.class);
        var diagnoses = mock(RootCauseDiagnosisRepository.class);
        var codec = mock(SnapshotCodecPort.class);
        var service = new PlanningScopeService(scopes, mock(DiagnosisRequestRepository.class), diagnoses, codec);
        UUID runId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        var result = mock(RootCauseDiagnosisEntity.class);
        when(result.evidenceSnapshotJson()).thenReturn("retained-snapshot");
        when(diagnoses.findById(diagnosisId)).thenReturn(Optional.of(result));
        var event = new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", runId,
                "190", 1, "44009", null, null, "CONFIRMED", 600, List.of(60, 60), Instant.EPOCH,
                List.of(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)), List.of(), List.of("event"), "v1");
        when(codec.decode("retained-snapshot")).thenReturn(new EvidenceSnapshot(event, new RootCauseContext("SIMULATION", runId, null, List.of(), List.of(), List.of()), List.of(), "SIMULATION"));
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", runId), new PlanningScope("LIVE", null),
                new PlanningScope("DEMO", UUID.randomUUID()));

        assertThat(service.includesDiagnosis(diagnosisId)).isTrue();
        assertThat(service.includesDiagnosis(diagnosisId)).isFalse();
        assertThat(service.includesDiagnosis(diagnosisId)).isFalse();
    }

    @Test
    void unknownSelectionCannotDefaultToLive() {
        assertThatThrownBy(() -> new PlanningScope("DEMO", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningScope("LIVE", UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosisPageBatchLoadsRetainedResultsAndSurvivesCorruptLegacySnapshots() {
        var scopes = mock(PlanningScopePort.class);
        var requests = mock(DiagnosisRequestRepository.class);
        var diagnoses = mock(RootCauseDiagnosisRepository.class);
        var codec = mock(SnapshotCodecPort.class);
        var service = new PlanningScopeService(scopes, requests, diagnoses, codec);
        UUID firstRequestId = UUID.randomUUID();
        UUID secondRequestId = UUID.randomUUID();
        var firstRequest = request(firstRequestId, UUID.randomUUID(), "input-snapshot");
        var legacyRequest = request(secondRequestId, UUID.randomUUID(), "corrupt-input");
        var pageable = PageRequest.of(0, 10);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", RUN_ID));
        when(requests.findScoped("SIMULATION", RUN_ID.toString(), pageable))
                .thenReturn(new PageImpl<>(List.of(firstRequest, legacyRequest), pageable, 2));
        var firstDiagnosis = diagnosis(UUID.randomUUID(), firstRequestId, "diagnosis-snapshot");
        var legacyDiagnosis = diagnosis(UUID.randomUUID(), secondRequestId, "corrupt-diagnosis");
        when(diagnoses.findAllByRequestIdIn(List.of(firstRequestId, secondRequestId)))
                .thenReturn(List.of(firstDiagnosis, legacyDiagnosis));
        when(codec.decode("input-snapshot")).thenReturn(snapshot());
        when(codec.decode("diagnosis-snapshot")).thenReturn(snapshot());
        when(codec.decode("corrupt-input")).thenThrow(new IllegalStateException("invalid legacy JSON"));
        when(codec.decode("corrupt-diagnosis")).thenThrow(new IllegalStateException("invalid legacy JSON"));
        when(codec.decodeStringList("evidence-json")).thenReturn(List.of("bunching:event", "traffic-incident:incident"));
        when(codec.decodeStringList("limitations-json")).thenReturn(List.of("Road impact is unverified."));

        var page = service.list(0, 10);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().likelyCause()).contains("Vehicle Breakdown", "Teck Whye Avenue");
        assertThat(page.items().getFirst().evidenceDetails()).hasSize(1);
        assertThat(page.items().getFirst().serviceId()).isEqualTo("190");
        assertThat(page.items().getLast().likelyCause()).contains("Details are unavailable");
        assertThat(page.items().getLast().serviceId()).isNull();
        assertThat(page.items().getLast().directionId()).isNull();
        org.mockito.Mockito.verify(diagnoses).findAllByRequestIdIn(List.of(firstRequestId, secondRequestId));
        org.mockito.Mockito.verify(diagnoses, never()).findByRequestId(any(UUID.class));
    }

    private DiagnosisRequest request(UUID id, UUID eventId, String snapshot) {
        var request = new DiagnosisRequest(id, EventType.BUS_BUNCHING, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, CONFIRMED);
        request.retainInput(snapshot);
        return request;
    }

    private RootCauseDiagnosisEntity diagnosis(UUID id, UUID requestId, String snapshot) {
        var diagnosis = mock(RootCauseDiagnosisEntity.class);
        when(diagnosis.id()).thenReturn(id);
        when(diagnosis.requestId()).thenReturn(requestId);
        when(diagnosis.causeCode()).thenReturn("TRAFFIC_INCIDENT");
        when(diagnosis.causeSubtype()).thenReturn("Vehicle Breakdown");
        when(diagnosis.likelyCause()).thenReturn("legacy UUID-heavy text");
        when(diagnosis.supportingEvidenceIdsJson()).thenReturn("evidence-json");
        when(diagnosis.limitationsJson()).thenReturn("limitations-json");
        when(diagnosis.evidenceSnapshotJson()).thenReturn(snapshot);
        return diagnosis;
    }

    private EvidenceSnapshot snapshot() {
        var event = new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", RUN_ID,
                "190", 1, "44251", UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 600,
                List.of(60, 60), CONFIRMED, List.of(CONFIRMED.minusSeconds(10), CONFIRMED),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("bunching:event"), "test-rule");
        var arrival = new ArrivalEvidence("bus-arrival:position", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "190", "44251", 1, CONFIRMED, CONFIRMED, new BigDecimal("1.3"), new BigDecimal("103.8"),
                null, "DD", "SIMULATION", RUN_ID, null);
        var incident = new IncidentEvidence("traffic-incident:incident", UUID.randomUUID(), "Vehicle Breakdown",
                "Breakdown near Teck Whye Avenue", new BigDecimal("1.3"), new BigDecimal("103.8"),
                CONFIRMED.minusSeconds(180), CONFIRMED.minusSeconds(30), "SIMULATION", RUN_ID);
        var context = new RootCauseContext("SIMULATION", RUN_ID, null, List.of(arrival), List.of(incident), List.of());
        return new EvidenceSnapshot(event, context, List.of("bunching:event", arrival.evidenceId(), incident.evidenceId()),
                "SIMULATION");
    }
}
