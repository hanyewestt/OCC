package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;

class PlanningScopeServiceTest {
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
}
