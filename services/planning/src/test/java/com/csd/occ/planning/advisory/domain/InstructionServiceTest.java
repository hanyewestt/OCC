package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class InstructionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");
    private final UUID runId = UUID.randomUUID();
    private final UUID captain = UUID.randomUUID();
    private final UUID disruptionId = UUID.randomUUID();
    private final UUID sourceObservationId = UUID.randomUUID();
    private final AdvisoryProposalRepository proposals = mock(AdvisoryProposalRepository.class);
    private final IssuedInstructionRepository instructions = mock(IssuedInstructionRepository.class);
    private final AdvisoryModelEvidenceRepository evidence = mock(AdvisoryModelEvidenceRepository.class);
    private final AdvisoryContextPort context = mock(AdvisoryContextPort.class);
    private final PlanningScopePort scopes = mock(PlanningScopePort.class);
    private final InstructionService service = new InstructionService(proposals, instructions, context, scopes,
            evidence, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AdvisoryContextPort.Bus bus = new AdvisoryContextPort.Bus("demo-190-b", "demo-190-b", "SBS5898U",
            "190", 1, captain, "ASSIGNED", new AdvisoryContextPort.Progress("44241", 5, NOW, 30, List.of(5), List.of(300)));
    private final AdvisoryCandidate caution = new AdvisoryCandidate(InstructionType.BE_CAUTIOUS,
            new InstructionParameters("44251", "Example Stop", 6, null, null, null), "Reported incident ahead");
    private AdvisoryContextPort.Hazard hazard;
    private AdvisoryProposal proposal;

    @BeforeEach
    void setUp() {
        hazard = hazard(sourceObservationId, List.of(new AdvisoryContextPort.Exposure(bus.busId(), "190", 1,
                "44251", "Example Stop", 6, "POINT_PROXIMITY", "Road relevance unverified")));
        proposal = new AdvisoryProposal(UUID.randomUUID(), runId, hazard, bus,
                new AdvisoryDraftPort.Result(caution, "synthetic", "{}", "{}"), "Disruption near Example Stop. Be cautious.", NOW);
        when(proposals.findForUpdate(proposal.id())).thenReturn(Optional.of(proposal));
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", runId));
        when(context.fleet(runId)).thenReturn(new AdvisoryContextPort.Fleet(runId, "RUNNING", List.of(bus), 1));
        when(context.hazard(disruptionId)).thenReturn(hazard);
        when(context.limits()).thenReturn(new AdvisoryContextPort.Limits(10, 600, 5, Duration.ofMinutes(5), Duration.ofHours(12)));
        when(evidence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(instructions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void captainCannotAcknowledgeAnotherCaptainsInstruction() {
        var otherBus = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), bus.serviceId(),
                bus.directionId(), UUID.randomUUID(), "ASSIGNED", bus.progress());
        var issued = issued(UUID.randomUUID(), otherBus, caution, NOW);
        when(instructions.findForUpdate(issued.id())).thenReturn(Optional.of(issued));

        assertThatThrownBy(() -> service.acknowledge(issued.id(), captain.toString()))
                .isInstanceOf(AdvisoryNotFoundException.class);
        assertThat(issued.acknowledgedAt()).isNull();
        verifyNoInteractions(context, scopes);
    }

    @Test
    void proposalOutsideTheCurrentRunCannotIssue() {
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", UUID.randomUUID()));
        assertThatThrownBy(() -> service.decide(proposal.id(), null, null, "controller", null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("outside the active simulation");
        verify(instructions, never()).saveAndFlush(any());
    }

    @Test
    void aBusWithoutAnAuthenticatedAssignmentCannotReceiveAnInstruction() {
        var unassigned = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(), bus.serviceId(),
                bus.directionId(), null, "CAPTAIN_ASSIGNMENT_UNAVAILABLE", bus.progress());
        when(context.fleet(runId)).thenReturn(new AdvisoryContextPort.Fleet(runId, "RUNNING", List.of(unassigned), 1));
        assertThatThrownBy(() -> service.decide(proposal.id(), null, null, "controller", null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("assignment unavailable");
        verify(instructions, never()).saveAndFlush(any());
    }

    @Test
    void sourceRevisionChangesRequireReviewingANewProposal() {
        when(context.hazard(disruptionId)).thenReturn(hazard(UUID.randomUUID(), 2L, hazard.exposures()));
        assertThatThrownBy(() -> service.decide(proposal.id(), null, null, "controller", null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("evidence changed");
        verify(instructions, never()).saveAndFlush(any());
    }

    @Test
    void unchangedTrafficReceiptDoesNotInvalidatePendingProposal() {
        var refreshed = hazard(UUID.randomUUID(), 1L, hazard.exposures());
        when(context.hazard(disruptionId)).thenReturn(refreshed);

        var issued = service.decide(proposal.id(), null, null, "controller", null);

        assertThat(issued.status()).isEqualTo("ISSUED");
        verify(instructions).saveAndFlush(any());
    }

    @Test
    void pendingQueueFiltersBeforeApplyingTheRequestedPage() {
        var request = org.springframework.data.domain.PageRequest.of(1, 20);
        var expected = new PageImpl<>(List.of(proposal), request, 21);
        when(proposals.findByDemoRunIdAndStatusOrderByCreatedAtAsc(runId, "PENDING", request)).thenReturn(expected);

        assertThat(service.proposals(1, 20, true)).isSameAs(expected);

        verify(proposals).findByDemoRunIdAndStatusOrderByCreatedAtAsc(runId, "PENDING", request);
        verify(proposals, never()).findByDemoRunIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void repeatedReassessmentForTheSameEvidenceKeepsOneProposalIdentity() {
        var replay = new AdvisoryProposal(UUID.randomUUID(), runId, hazard, bus,
                new AdvisoryDraftPort.Result(caution, "synthetic", "{}", "{}"), "Updated preview", NOW);

        assertThat(replay.id()).isEqualTo(proposal.id());
    }

    @Test
    void repeatedIdenticalDecisionReturnsTheOriginalInstructionWithoutIssuingAgain() {
        var first = service.decide(proposal.id(), null, null, "controller", null);
        when(instructions.findById(proposal.id())).thenReturn(Optional.of(first));
        var replay = service.decide(proposal.id(), null, null, "controller", null);
        assertThat(replay).isSameAs(first);
        assertThat(replay.issuedAt()).isEqualTo(NOW);
        assertThat(replay.message()).isEqualTo(proposal.preview());
        verify(instructions, times(1)).saveAndFlush(any());
    }

    @Test
    void staleSpeedProposalCannotIssueAfterDownstreamExposureDisappears() {
        when(context.hazard(disruptionId)).thenReturn(hazard(sourceObservationId, List.of()));
        assertThatThrownBy(() -> service.decide(proposal.id(), speed(), "Controller replacement", "controller", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no current downstream exposure");
        verify(instructions, never()).saveAndFlush(any());
    }

    @Test
    void acknowledgementReplayKeepsTheOriginalTimeAndSpeedWindow() {
        var issued = issued(UUID.randomUUID(), bus, speed(), NOW);
        when(instructions.findForUpdate(issued.id())).thenReturn(Optional.of(issued));
        var first = service.acknowledge(issued.id(), captain.toString());
        var replay = service.acknowledge(issued.id(), captain.toString());
        assertThat(replay).isSameAs(first);
        assertThat(replay.acknowledgedAt()).isEqualTo(NOW);
        assertThat(replay.applicableUntil()).isEqualTo(NOW.plusSeconds(300));
        verify(context, times(1)).fleet(runId);
    }

    @Test
    void firstAcknowledgementAtItsDeadlineIsRejected() {
        var issued = issued(UUID.randomUUID(), bus, speed(), NOW.minusSeconds(300));
        when(instructions.findForUpdate(issued.id())).thenReturn(Optional.of(issued));
        assertThatThrownBy(() -> service.acknowledge(issued.id(), captain.toString()))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(issued.acknowledgedAt()).isNull();
    }

    @Test
    void historyRefreshesAnExpiredWindowWithoutWaitingForCaptainPolling() {
        var issued = issued(UUID.randomUUID(), bus, speed(), NOW.minusSeconds(300));
        when(instructions.findByDemoRunIdOrderByIssuedAtDesc(eq(runId), any())).thenReturn(new PageImpl<>(List.of(issued)));
        var result = service.history(0, 20);
        assertThat(result.getContent().getFirst().status()).isEqualTo("EXPIRED");
        assertThat(result.getContent().getFirst().inactiveReason()).isEqualTo("VALIDITY_EXPIRED");
        verify(context, never()).hazard(any());
    }

    @Test
    void anotherMovementInstructionRequiresExplicitReplacement() {
        var previous = issued(UUID.randomUUID(), bus, speed(), NOW);
        when(instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(eq(runId), eq(bus.busId()), anyList()))
                .thenReturn(List.of(previous));
        assertThatThrownBy(() -> service.decide(proposal.id(), speed(), "Controller replacement", "controller", null))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining(previous.id().toString());
        assertThat(previous.status()).isEqualTo("ISSUED");
        verify(instructions, never()).saveAndFlush(any());
    }

    @Test
    void explicitReplacementCancelsThePreviousMovementInstruction() {
        var previous = issued(UUID.randomUUID(), bus, speed(), NOW);
        when(instructions.findForUpdate(previous.id())).thenReturn(Optional.of(previous));
        when(instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(eq(runId), eq(bus.busId()), anyList()))
                .thenReturn(List.of(previous));
        var replacement = service.decide(proposal.id(), speed(), "Controller replacement", "controller", previous.id());
        assertThat(previous.status()).isEqualTo("CANCELLED");
        assertThat(replacement.status()).isEqualTo("ISSUED");
        assertThat(replacement.id()).isEqualTo(proposal.id());
    }

    private AdvisoryCandidate speed() {
        return new AdvisoryCandidate(InstructionType.REDUCE_SPEED,
                new InstructionParameters(null, null, null, null, 5, 300), "Synthetic pacing advice");
    }

    private IssuedInstruction issued(UUID id, AdvisoryContextPort.Bus target, AdvisoryCandidate candidate, Instant at) {
        return new IssuedInstruction(id, disruptionId, runId, target, candidate, "Approved instruction", "SIMULATION",
                "controller", at, Duration.ofMinutes(5));
    }

    private AdvisoryContextPort.Hazard hazard(UUID observation, List<AdvisoryContextPort.Exposure> exposure) {
        return hazard(observation, 1L, exposure);
    }

    private AdvisoryContextPort.Hazard hazard(UUID observation, long sourceRevision,
            List<AdvisoryContextPort.Exposure> exposure) {
        var facts = new HazardEventFacts.Fact("TRAFFIC_INCIDENT", "synthetic-source", "OBSERVED", NOW.minusSeconds(180),
                null, NOW.plusSeconds(3600), "Synthetic incident", "Provider message", null, null, List.of(),
                null, null, null, null, List.of());
        return new AdvisoryContextPort.Hazard(disruptionId, "TRAFFIC_INCIDENT", "ACTIVE", null, "SIMULATION", runId,
                observation, sourceRevision, NOW, facts, exposure, List.of("Road relevance unverified"));
    }
}
