package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningScopeServiceTest {
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
