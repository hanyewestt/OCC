package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.diagnosis.domain.DiagnosisCompletionService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisPolicySettings;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.EventType;
import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import com.csd.occ.planning.diagnosis.domain.SnapshotCodecPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HazardAdvisoryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

    @Test
    void delayedReassessmentFromClosedRunCannotTargetReusedBusId() {
        UUID oldRunId = UUID.randomUUID();
        UUID currentRunId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String busId = "demo-190-b";
        var facts = new HazardEventFacts(eventId, disruptionId, "REASSESSED", null, "OBSERVATION_ONLY", null,
                oldRunId, observationId, 1L, null, busId, NOW, null, null);
        var submission = new ProducerEventSubmission(DiagnosisRequestService.SCHEMA_VERSION, EventType.HAZARD,
                "disruption", eventId, disruptionId, "OBSERVATION_ONLY", null, NOW, "hazard-observation-v1",
                List.of(observationId.toString()), null, null, facts);
        var request = new DiagnosisRequest(requestId, EventType.HAZARD, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, NOW);
        request.retainInput("reassessment-snapshot");

        var requests = mock(DiagnosisRequestRepository.class);
        var snapshots = mock(SnapshotCodecPort.class);
        var context = mock(AdvisoryContextPort.class);
        var scopes = mock(PlanningScopePort.class);
        var drafts = mock(AdvisoryDraftPort.class);
        var proposals = mock(AdvisoryProposalService.class);
        var completion = mock(DiagnosisCompletionService.class);
        var reconciliation = mock(AdvisoryReconciliationService.class);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(snapshots.decodeProducerEvent("reassessment-snapshot")).thenReturn(submission);
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", currentRunId));

        var exposure = new AdvisoryContextPort.Exposure(busId, "190", 1, "S6", "Harbor Road", 6,
                "STOP_SEQUENCE", null);
        var hazard = new AdvisoryContextPort.Hazard(disruptionId, "FLOOD", "ACTIVE", null,
                "OBSERVATION_ONLY", null, observationId, 1L, NOW,
                new HazardEventFacts.Fact("FLOOD", "flood-1", "OPENED", NOW, null, null,
                        "Flood", "Water near the stop", null, null, List.of(), null, null, null, null, List.of()),
                List.of(exposure), List.of());
        var bus = new AdvisoryContextPort.Bus(busId, "trip", "SBS0000A", "190", 1, UUID.randomUUID(), "ASSIGNED",
                new AdvisoryContextPort.Progress("S5", 5, NOW, 30, List.of(5), List.of(300)));
        when(context.hazard(disruptionId)).thenReturn(hazard);
        when(context.fleet(currentRunId)).thenReturn(new AdvisoryContextPort.Fleet(currentRunId, "RUNNING", List.of(bus), 1));
        when(context.limits()).thenReturn(new AdvisoryContextPort.Limits(10, 900, 10, Duration.ofMinutes(5), Duration.ofHours(12)));
        when(drafts.propose(hazard, bus)).thenReturn(new AdvisoryDraftPort.Result(
                new AdvisoryCandidate(InstructionType.BE_CAUTIOUS,
                        new InstructionParameters("S6", "Harbor Road", 6, null, null, null), "Near reported flood"),
                "synthetic", "input", "output"));

        var processor = new HazardAdvisoryProcessor(requests, snapshots, context, scopes, drafts, proposals, completion,
                new DiagnosisPolicySettings(Duration.ofHours(1), 5, 0, 3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                Clock.fixed(NOW, ZoneOffset.UTC), reconciliation);

        assertThat(processor.processIfHazard(requestId)).isTrue();

        verify(proposals).record(requestId, List.of());
        verifyNoInteractions(context, drafts, completion);
    }
}
