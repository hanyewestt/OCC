package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationDecisionServiceTest {
    private static final UUID RECOMMENDATION_ID = UUID.fromString("0d319a5b-6611-49cc-8462-b5c64f65d122");
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Mock
    private RecommendationDecisionPort decisions;

    @Mock
    private RecommendationRecordPort recommendations;

    private RecommendationRecord actionableRecommendation;

    @BeforeEach
    void createActionableRecommendation() {
        actionableRecommendation = org.mockito.Mockito.mock(RecommendationRecord.class);
        var recommendation = org.mockito.Mockito.mock(ValidatedMitigationRecommendation.class);
        lenient().when(actionableRecommendation.classification()).thenReturn(SourceClassification.SIMULATION);
        lenient().when(actionableRecommendation.recommendation()).thenReturn(recommendation);
        lenient().when(recommendation.status()).thenReturn(RecommendationStatus.RECOMMENDED);
    }

    @Test
    void recordsTheAuthenticatedActorsDecisionAtTheInjectedTime() {
        var service = service();
        when(recommendations.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.of(actionableRecommendation));
        when(decisions.recordIfAbsent(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                Optional.of(invocation.getArgument(0)));

        var result = service.decide(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                "controller-subject", java.util.List.of(), null);

        assertThat(result.action()).isEqualTo(RecommendationDecisionAction.APPROVED);
        assertThat(result.actorSubject()).isEqualTo("controller-subject");
        assertThat(result.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void sameDecisionCanBeSafelyRetried() {
        var service = service();
        var persisted = new RecommendationDecision(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                "first-controller", java.util.List.of(), null, NOW.minusSeconds(10));
        when(recommendations.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.of(actionableRecommendation));
        when(decisions.recordIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(decisions.findByRecommendationId(RECOMMENDATION_ID)).thenReturn(Optional.of(persisted));

        assertThat(service.decide(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                "retry-controller", java.util.List.of(), null))
                .isEqualTo(persisted);
    }

    @Test
    void oppositeDecisionConflictsWithTheFirstDecision() {
        var service = service();
        var persisted = new RecommendationDecision(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                "first-controller", java.util.List.of(), null, NOW.minusSeconds(10));
        when(recommendations.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.of(actionableRecommendation));
        when(decisions.recordIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(decisions.findByRecommendationId(RECOMMENDATION_ID)).thenReturn(Optional.of(persisted));

        assertThatThrownBy(() -> service.decide(RECOMMENDATION_ID,
                RecommendationDecisionAction.REJECTED, "other-controller",
                java.util.List.of(new ControllerBusInstruction("SBS5898U", "Hold for 60 seconds.")),
                "Use a shorter hold."))
                .isInstanceOf(RecommendationDecisionService.RecommendationDecisionConflictException.class);
    }

    @Test
    void rejectsUnknownRecommendations() {
        var service = service();
        when(recommendations.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(RECOMMENDATION_ID,
                RecommendationDecisionAction.APPROVED, "controller-subject", java.util.List.of(), null))
                .isInstanceOf(RecommendationDecisionService.RecommendationNotFoundException.class);
        verify(decisions, org.mockito.Mockito.never()).recordIfAbsent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsRejectedRecommendationWithControllerInstructionsAndRationale() {
        var service = service();
        var instructions = java.util.List.of(
                new ControllerBusInstruction("SBS5898U", "Hold at the next stop for 60 seconds."),
                new ControllerBusInstruction("Bus 2", "Proceed at the scheduled pace."));
        when(recommendations.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.of(actionableRecommendation));
        when(decisions.recordIfAbsent(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                Optional.of(invocation.getArgument(0)));

        var result = service.decide(RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED,
                "controller-subject", instructions, "A shorter hold reduces passenger delay.");

        assertThat(result.overrideInstructions()).isEqualTo(instructions);
        assertThat(result.controllerRationale()).isEqualTo("A shorter hold reduces passenger delay.");
    }

    private RecommendationDecisionService service() {
        return new RecommendationDecisionService(decisions, recommendations,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

}
