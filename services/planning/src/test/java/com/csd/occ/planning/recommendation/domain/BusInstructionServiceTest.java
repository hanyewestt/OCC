package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusInstructionServiceTest {
    private static final UUID RECOMMENDATION_ID = UUID.fromString("0d319a5b-6611-49cc-8462-b5c64f65d122");
    private static final Instant DECIDED_AT = Instant.parse("2026-10-24T04:00:00Z");
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-10-24T04:01:00Z");
    private static final UUID INSTRUCTION_ID = UUID.fromString("17be89f4-e22d-36a7-ac09-ae447f1b3084");

    @Mock
    private BusInstructionPort instructions;

    private BusInstructionService service;

    @BeforeEach
    void createService() {
        service = new BusInstructionService(instructions, Clock.fixed(ACKNOWLEDGED_AT, ZoneOffset.UTC));
    }

    @Test
    void approvalPublishesTheValidatedRecommendationAsSent() {
        RecommendationRecord record = org.mockito.Mockito.mock(RecommendationRecord.class);
        ValidatedMitigationRecommendation recommendation = org.mockito.Mockito.mock(
                ValidatedMitigationRecommendation.class);
        when(record.recommendation()).thenReturn(recommendation);
        when(recommendation.targetBusReference()).thenReturn("SBS5898U");
        when(recommendation.mitigationType()).thenReturn(MitigationType.HOLD);
        when(recommendation.adjustmentSeconds()).thenReturn(120);
        when(recommendation.controlStopCode()).thenReturn("44241");
        RecommendationDecision decision = new RecommendationDecision(RECOMMENDATION_ID,
                RecommendationDecisionAction.APPROVED, "controller-subject", List.of(), null, DECIDED_AT);

        service.issueFor(record, decision);

        ArgumentCaptor<BusInstruction> instruction = ArgumentCaptor.forClass(BusInstruction.class);
        verify(instructions).saveIfAbsent(instruction.capture());
        assertThat(instruction.getValue().recommendationId()).isEqualTo(RECOMMENDATION_ID);
        assertThat(instruction.getValue().busReference()).isEqualTo("SBS5898U");
        assertThat(instruction.getValue().instruction()).isEqualTo("Hold for 120 seconds at stop 44241.");
        assertThat(instruction.getValue().status()).isEqualTo(BusInstructionStatus.SENT);
        assertThat(instruction.getValue().sentAt()).isEqualTo(DECIDED_AT);
    }

    @Test
    void rejectedRecommendationPublishesControllerReplacementInstructions() {
        RecommendationRecord record = org.mockito.Mockito.mock(RecommendationRecord.class);
        List<ControllerBusInstruction> replacements = List.of(
                new ControllerBusInstruction("SBS5898U", "Proceed without holding."),
                new ControllerBusInstruction("SBS6123A", "Hold for 120 seconds."));
        RecommendationDecision decision = new RecommendationDecision(RECOMMENDATION_ID,
                RecommendationDecisionAction.REJECTED, "controller-subject", replacements,
                "Use both buses.", DECIDED_AT);

        service.issueFor(record, decision);

        ArgumentCaptor<BusInstruction> instruction = ArgumentCaptor.forClass(BusInstruction.class);
        verify(instructions, org.mockito.Mockito.times(2)).saveIfAbsent(instruction.capture());
        assertThat(instruction.getAllValues())
                .extracting(BusInstruction::busReference)
                .containsExactly("SBS5898U", "SBS6123A");
    }

    @Test
    void readQueryIsTrimmedAndPaginated() {
        when(instructions.findByBusReference("SBS5898U", 20, 20)).thenReturn(List.of());

        assertThat(service.findForBus(" SBS5898U ", 1, 20)).isEmpty();

        verify(instructions).findByBusReference("SBS5898U", 20, 20);
    }

    @Test
    void invalidReadPaginationIsRejected() {
        assertThatThrownBy(() -> service.findForBus("SBS5898U", -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findForBus("SBS5898U", 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void captainAcknowledgesSentInstructionWithServerTime() {
        BusInstruction sent = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds.", DECIDED_AT);
        when(instructions.findById(INSTRUCTION_ID)).thenReturn(Optional.of(sent));
        when(instructions.acknowledgeIfSent(any())).thenReturn(true);

        BusInstruction acknowledged = service.acknowledge(INSTRUCTION_ID, "captain-subject");

        assertThat(acknowledged.status()).isEqualTo(BusInstructionStatus.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedAt()).contains(ACKNOWLEDGED_AT);
        assertThat(acknowledged.acknowledgedBy()).contains("captain-subject");
        verify(instructions).acknowledgeIfSent(acknowledged);
    }

    @Test
    void originalCaptainCanReplayAcknowledgement() {
        BusInstruction acknowledged = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds.", DECIDED_AT)
                .acknowledge("captain-subject", ACKNOWLEDGED_AT);
        when(instructions.findById(INSTRUCTION_ID)).thenReturn(Optional.of(acknowledged));

        assertThat(service.acknowledge(INSTRUCTION_ID, "captain-subject")).isSameAs(acknowledged);
    }

    @Test
    void differentCaptainCannotReplaceAcknowledgement() {
        BusInstruction acknowledged = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds.", DECIDED_AT)
                .acknowledge("first-captain", ACKNOWLEDGED_AT);
        when(instructions.findById(INSTRUCTION_ID)).thenReturn(Optional.of(acknowledged));

        assertThatThrownBy(() -> service.acknowledge(INSTRUCTION_ID, "second-captain"))
                .isInstanceOf(BusInstructionAcknowledgementConflictException.class);
    }

    @Test
    void concurrentWinnerDeterminesReplayOutcome() {
        BusInstruction sent = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds.", DECIDED_AT);
        BusInstruction winner = sent.acknowledge("other-captain", ACKNOWLEDGED_AT);
        when(instructions.findById(INSTRUCTION_ID)).thenReturn(Optional.of(sent), Optional.of(winner));
        when(instructions.acknowledgeIfSent(any())).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(INSTRUCTION_ID, "captain-subject"))
                .isInstanceOf(BusInstructionAcknowledgementConflictException.class);
    }

    @Test
    void unknownInstructionIsRejected() {
        when(instructions.findById(INSTRUCTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(INSTRUCTION_ID, "captain-subject"))
                .isInstanceOf(BusInstructionNotFoundException.class);
    }
}
