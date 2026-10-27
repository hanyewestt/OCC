package com.csd.occ.disruption.hazard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.ConsumerState;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutbox;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.EncodedPlanningEventSubmission;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HazardReassessmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

    @Test
    void reassessmentQueuesOneDurableTargetedEventForCurrentBusProgress() {
        var selectedRun = UUID.randomUUID();
        var disruptionId = UUID.randomUUID();
        var observationId = UUID.randomUUID();
        var busId = "demo-190-b";
        var state = mock(ConsumerState.class);
        when(state.mode()).thenReturn("DEMO");
        when(state.demoRunId()).thenReturn(selectedRun);
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(state);
        var fact = new HazardPollPort.Fact("TRAFFIC_INCIDENT", "incident-1", "OBSERVED", NOW,
                null, null, "Traffic incident", "Lane blocked", null, null, List.of(), null, null, null, null,
                List.of(new HazardPollPort.Location("POINT", "incident-1", "Lane blocked", new BigDecimal("1.38"),
                        new BigDecimal("103.75"), null, "INCIDENT_POINT", "PROVIDER_SUPPLIED")));
        var exposure = new HazardExposurePolicy.Exposure(busId, "190", 1, "44251", "Example Stop", 5, 10,
                "INCIDENT_POINT", "Candidate association");
        var view = new HazardReadService.View(disruptionId, "TRAFFIC_INCIDENT", "ACTIVE", null,
                "OBSERVATION_ONLY", null, observationId, 4L, NOW, fact, List.of(exposure), List.of());
        var reads = mock(HazardReadService.class);
        when(reads.list(null, 0, 100)).thenReturn(new HazardReadService.Page(List.of(view), 0, 100, 1, List.of()));
        var hazards = mock(HazardStateRepository.class);
        when(hazards.eventExists(any())).thenReturn(false, true);
        when(hazards.recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        var outbox = mock(DiagnosisOutboxRepository.class);
        when(outbox.findByEventId(any())).thenReturn(Optional.empty());
        var codec = mock(PlanningEventSubmissionPort.class);
        when(codec.encodeHazard(any())).thenReturn(new EncodedPlanningEventSubmission(
                "{}", "a".repeat(64), "planning-event-v1"));
        var transactionManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var service = new HazardReassessmentService(selection, reads, hazards, outbox, codec,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

        assertThat(service.enqueueExposedBuses()).isEqualTo(1);
        assertThat(service.enqueueExposedBuses()).isZero();

        var snapshot = ArgumentCaptor.forClass(HazardEventSnapshot.class);
        verify(codec).encodeHazard(snapshot.capture());
        assertThat(snapshot.getValue().action()).isEqualTo("REASSESSED");
        assertThat(snapshot.getValue().sourceRevision()).isEqualTo(4L);
        assertThat(snapshot.getValue().targetRunId()).isEqualTo(selectedRun);
        assertThat(snapshot.getValue().targetBusId()).isEqualTo(busId);
        verify(hazards).recordEvent(any(), org.mockito.ArgumentMatchers.eq(disruptionId),
                org.mockito.ArgumentMatchers.eq("TRAFFIC_INCIDENT"), org.mockito.ArgumentMatchers.eq("REASSESSED"),
                org.mockito.ArgumentMatchers.eq("OBSERVATION_ONLY"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(observationId), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.eq("{}"));
        verify(outbox, times(1)).save(any(DiagnosisOutbox.class));
    }
}
