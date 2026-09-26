package com.csd.occ.planning.recommendationengine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BunchingRecommendationOrchestratorTest {

    private static final UUID RECOMMENDATION_ID = UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID DIAGNOSIS_ID = UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");

    @Test
    void disabledCandidateGenerationReturnsDeterministicRecommendationWithoutCallingProvider() {
        AtomicInteger calls = new AtomicInteger();
        MitigationCandidateGeneratorPort generator = request -> {
            calls.incrementAndGet();
            return generated(matchingCandidate("Generated rationale."));
        };

        var result = orchestrator(false, Optional.of(generator)).recommend(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertThat(result).hasValueSatisfying(recommendation ->
                assertThat(recommendation.explanation().rationale())
                        .startsWith("Hold the following bus"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void missingCandidateProviderReturnsDeterministicRecommendation() {
        var result = orchestrator(true, Optional.empty()).recommend(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertThat(result).hasValueSatisfying(recommendation -> {
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(120);
        });
    }

    @Test
    void matchingValidatedCandidateEnrichesDeterministicRecommendationRationale() {
        MitigationCandidateGeneratorPort generator = request ->
                generated(matchingCandidate("Explain the approved hold in controller-friendly language."));

        var result = orchestrator(true, Optional.of(generator)).evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertThat(result).hasValueSatisfying(outcome -> {
            var recommendation = outcome.recommendation();
            assertThat(recommendation.explanation().rationale())
                    .isEqualTo("Explain the approved hold in controller-friendly language.");
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(120);
            assertThat(recommendation.expectedImpact()).isEqualTo(impact());
            assertThat(recommendation.evidenceIds())
                    .containsExactly(
                            "arrival-observation-1",
                            "arrival-observation-2",
                            "diagnosis-evidence-1",
                            "operations-evidence-1");
            assertThat(outcome.modelInvocation()).hasValueSatisfying(audit -> {
                assertThat(audit.outcome()).isEqualTo(ModelInvocationOutcome.ACCEPTED);
                assertThat(audit.deterministicFallbackUsed()).isFalse();
                assertThat(audit.candidate()).contains(matchingCandidate(
                        "Explain the approved hold in controller-friendly language."));
            });
        });
    }

    @Test
    void emptyCandidateOutputReturnsDeterministicRecommendation() {
        MitigationCandidateGeneratorPort generator = request ->
                new MitigationCandidateGenerationResult.Empty(metadata());

        var result = orchestrator(true, Optional.of(generator)).evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertFallbackWithAudit(result, ModelInvocationOutcome.EMPTY_RESPONSE, Optional.empty());
    }

    @Test
    void rejectedCandidateReturnsDeterministicRecommendation() {
        MitigationCandidateGeneratorPort generator = request -> generated(new MitigationCandidate(
                CandidateMitigationType.HOLD,
                "UNAPPROVED-BUS",
                "44241",
                120,
                "Use an unapproved bus."));

        var result = orchestrator(true, Optional.of(generator)).evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertFallbackWithAudit(
                result,
                ModelInvocationOutcome.REJECTED,
                Optional.of(MitigationCandidateRejectionReason.TARGET_BUS_NOT_APPROVED));
    }

    @Test
    void validCandidateThatDoesNotMatchSelectedActionReturnsDeterministicRecommendation() {
        MitigationCandidateGeneratorPort generator = request -> generated(new MitigationCandidate(
                CandidateMitigationType.HOLD,
                "SBS5898U",
                "44241",
                60,
                "Use a shorter hold than the simulated action."));

        var result = orchestrator(true, Optional.of(generator)).evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertFallbackWithAudit(result, ModelInvocationOutcome.MISMATCHED, Optional.empty());
    }

    @Test
    void providerFailureReturnsDeterministicRecommendation() {
        MitigationCandidateGeneratorPort generator = request ->
                new MitigationCandidateGenerationResult.Failed(
                        metadata(),
                        ModelInvocationFailureCategory.PROVIDER_ERROR);

        var result = orchestrator(true, Optional.of(generator)).evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertFallbackWithAudit(result, ModelInvocationOutcome.PROVIDER_FAILURE, Optional.empty());
        assertThat(result.orElseThrow().modelInvocation().orElseThrow().failureCategory())
                .contains(ModelInvocationFailureCategory.PROVIDER_ERROR);
    }

    @Test
    void nonActionableInputReturnsNoRecommendationWithoutCallingProvider() {
        AtomicInteger calls = new AtomicInteger();
        MitigationCandidateGeneratorPort generator = request -> {
            calls.incrementAndGet();
            return generated(matchingCandidate("Generated rationale."));
        };

        var result = orchestrator(true, Optional.of(generator)).recommend(
                RECOMMENDATION_ID,
                SourceClassification.OBSERVATION_ONLY,
                event(BunchingEventStatus.CONFIRMED),
                diagnosis(),
                context(),
                impact());

        assertThat(result).isEmpty();
        assertThat(calls).hasValue(0);
    }

    private BunchingRecommendationOrchestrator orchestrator(
            boolean enabled,
            Optional<MitigationCandidateGeneratorPort> generator) {
        return new BunchingRecommendationOrchestrator(
                enabled,
                generator,
                new BunchingMitigationCandidateValidator(),
                new BunchingRecommendationPolicy());
    }

    private void assertDeterministicFallback(Optional<ValidatedMitigationRecommendation> result) {
        assertThat(result).hasValueSatisfying(recommendation -> {
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(120);
            assertThat(recommendation.explanation().rationale())
                    .startsWith("Hold the following bus");
        });
    }

    private void assertFallbackWithAudit(
            Optional<BunchingRecommendationOutcome> result,
            ModelInvocationOutcome expectedOutcome,
            Optional<MitigationCandidateRejectionReason> expectedRejectionReason) {
        assertThat(result).hasValueSatisfying(outcome -> {
            ValidatedMitigationRecommendation recommendation = outcome.recommendation();
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(120);
            assertThat(recommendation.explanation().rationale()).startsWith("Hold the following bus");
            assertThat(outcome.modelInvocation()).hasValueSatisfying(audit -> {
                assertThat(audit.outcome()).isEqualTo(expectedOutcome);
                assertThat(audit.rejectionReason()).isEqualTo(expectedRejectionReason);
                assertThat(audit.deterministicFallbackUsed()).isTrue();
            });
        });
    }

    private MitigationCandidateGenerationResult generated(MitigationCandidate candidate) {
        return new MitigationCandidateGenerationResult.Generated(metadata(), candidate);
    }

    private ModelInvocationMetadata metadata() {
        Instant invokedAt = Instant.parse("2026-09-28T00:00:00Z");
        return new ModelInvocationMetadata(
                UUID.fromString("7778bb14-2fe4-4fcc-9149-642e1fd27c00"),
                ModelInvocationProvider.GEMINI,
                "gemini-3.7-flash",
                "bunching-candidate-v1",
                "mitigation-candidate-v1",
                Optional.of("synthetic-response"),
                invokedAt,
                invokedAt.plusMillis(125),
                125,
                Optional.of(new ModelTokenUsage(
                        Optional.of(100),
                        Optional.of(25),
                        Optional.of(125))));
    }

    private MitigationCandidate matchingCandidate(String rationale) {
        return new MitigationCandidate(
                CandidateMitigationType.HOLD,
                "SBS5898U",
                "44241",
                120,
                rationale);
    }

    private ConfirmedBunchingEvent event(BunchingEventStatus status) {
        return new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44251",
                status,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("arrival-observation-1", "arrival-observation-2"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                DIAGNOSIS_ID,
                "A vehicle breakdown delayed the leader.",
                new BigDecimal("0.82"),
                List.of("diagnosis-evidence-1"),
                List.of("Synthetic test diagnosis."));
    }

    private ValidatedMitigationContext context() {
        return new ValidatedMitigationContext(
                "SBS5898U",
                "44241",
                FollowingBusState.READY_TO_DEPART,
                120,
                75,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED,
                List.of("operations-evidence-1"));
    }

    private SimulatedImpactEstimate impact() {
        return new SimulatedImpactEstimate(
                221,
                341,
                38,
                24,
                new BigDecimal("190"),
                new BigDecimal("128"));
    }
}
