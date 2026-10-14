package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BunchingRecommendationOrchestrator {

    private final boolean candidateGenerationEnabled;
    private final Optional<MitigationCandidateGeneratorPort> candidateGenerator;
    private final BunchingMitigationCandidateValidator candidateValidator;
    private final BunchingRecommendationPolicy deterministicPolicy;

    public BunchingRecommendationOrchestrator(
            boolean candidateGenerationEnabled,
            Optional<MitigationCandidateGeneratorPort> candidateGenerator,
            BunchingMitigationCandidateValidator candidateValidator,
            BunchingRecommendationPolicy deterministicPolicy) {
        this.candidateGenerationEnabled = candidateGenerationEnabled;
        this.candidateGenerator = Objects.requireNonNull(
                candidateGenerator,
                "candidateGenerator is required");
        this.candidateValidator = Objects.requireNonNull(candidateValidator, "candidateValidator is required");
        this.deterministicPolicy = Objects.requireNonNull(deterministicPolicy, "deterministicPolicy is required");
    }

    public Optional<ValidatedMitigationRecommendation> recommend(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis,
            ValidatedMitigationContext context,
            SimulatedImpactEstimate impact) {
        return evaluate(recommendationId, classification, event, diagnosis, context, impact)
                .map(BunchingRecommendationOutcome::recommendation);
    }

    public Optional<BunchingRecommendationOutcome> evaluate(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis,
            ValidatedMitigationContext context,
            SimulatedImpactEstimate impact) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(impact, "impact is required");

        Optional<ValidatedMitigationRecommendation> deterministicRecommendation = deterministicPolicy.evaluate(
                recommendationId,
                classification,
                event,
                diagnosis,
                context,
                impact);
        if (deterministicRecommendation.isEmpty()
                || !candidateGenerationEnabled
                || candidateGenerator.isEmpty()) {
            return deterministicRecommendation.map(recommendation ->
                    new BunchingRecommendationOutcome(recommendation, Optional.empty()));
        }

        MitigationCandidateRequest candidateRequest = new MitigationCandidateRequest(
                classification,
                event,
                diagnosis,
                context);
        ValidatedMitigationRecommendation fallback = deterministicRecommendation.orElseThrow();
        Optional<MitigationCandidateGenerationResult> generation = generateCandidate(candidateRequest);
        if (generation.isEmpty()) {
            return Optional.of(new BunchingRecommendationOutcome(fallback, Optional.empty()));
        }

        MitigationCandidateGenerationResult result = generation.orElseThrow();
        if (result instanceof MitigationCandidateGenerationResult.Empty empty) {
            return withAudit(fallback, new ModelInvocationRecord(
                    recommendationId,
                    empty.metadata(),
                    ModelInvocationOutcome.EMPTY_RESPONSE,
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    Optional.empty()));
        }
        if (result instanceof MitigationCandidateGenerationResult.Failed failed) {
            return withAudit(fallback, new ModelInvocationRecord(
                    recommendationId,
                    failed.metadata(),
                    ModelInvocationOutcome.PROVIDER_FAILURE,
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    Optional.of(failed.failureCategory())));
        }

        MitigationCandidateGenerationResult.Generated generated =
                (MitigationCandidateGenerationResult.Generated) result;
        MitigationCandidate candidate = generated.candidate();
        MitigationCandidateValidationResult validation = candidateValidator.validate(candidateRequest, candidate);
        if (validation instanceof MitigationCandidateValidationResult.Rejected rejected) {
            return withAudit(fallback, new ModelInvocationRecord(
                    recommendationId,
                    generated.metadata(),
                    ModelInvocationOutcome.REJECTED,
                    Optional.of(candidate),
                    Optional.of(rejected.reason()),
                    true,
                    Optional.empty()));
        }
        if (!matchesDeterministicAction(candidate, fallback)) {
            return withAudit(fallback, new ModelInvocationRecord(
                    recommendationId,
                    generated.metadata(),
                    ModelInvocationOutcome.MISMATCHED,
                    Optional.of(candidate),
                    Optional.empty(),
                    true,
                    Optional.empty()));
        }

        ValidatedMitigationRecommendation enriched = withCandidateRationale(fallback, candidate.rationale());
        return withAudit(enriched, new ModelInvocationRecord(
                recommendationId,
                generated.metadata(),
                ModelInvocationOutcome.ACCEPTED,
                Optional.of(candidate),
                Optional.empty(),
                false,
                Optional.empty()));
    }

    private Optional<BunchingRecommendationOutcome> withAudit(
            ValidatedMitigationRecommendation recommendation,
            ModelInvocationRecord audit) {
        return Optional.of(new BunchingRecommendationOutcome(recommendation, Optional.of(audit)));
    }

    private Optional<MitigationCandidateGenerationResult> generateCandidate(MitigationCandidateRequest request) {
        try {
            return Optional.of(candidateGenerator.orElseThrow().generate(request));
        } catch (RuntimeException providerFailure) {
            return Optional.empty();
        }
    }

    private boolean matchesDeterministicAction(
            MitigationCandidate candidate,
            ValidatedMitigationRecommendation deterministicRecommendation) {
        MitigationType candidateType = switch (candidate.mitigationType()) {
            case HOLD -> MitigationType.HOLD;
            case DWELL_ADJUSTMENT -> MitigationType.DWELL_ADJUSTMENT;
        };
        return candidateType == deterministicRecommendation.mitigationType()
                && candidate.targetBusReference().equals(deterministicRecommendation.targetBusReference())
                && candidate.controlStopCode().equals(deterministicRecommendation.controlStopCode())
                && candidate.requestedAdjustmentSeconds() == deterministicRecommendation.adjustmentSeconds();
    }

    private ValidatedMitigationRecommendation withCandidateRationale(
            ValidatedMitigationRecommendation recommendation,
            String candidateRationale) {
        RecommendationExplanation deterministicExplanation = recommendation.explanation();
        RecommendationExplanation enrichedExplanation = new RecommendationExplanation(
                candidateRationale,
                deterministicExplanation.likelyCause(),
                deterministicExplanation.diagnosisConfidence(),
                deterministicExplanation.tradeOffs(),
                deterministicExplanation.limitations());
        return new ValidatedMitigationRecommendation(
                recommendation.recommendationId(),
                recommendation.incidentType(),
                recommendation.status(),
                recommendation.mitigationType(),
                recommendation.targetBusReference(),
                recommendation.controlStopCode(),
                recommendation.adjustmentSeconds(),
                enrichedExplanation,
                recommendation.expectedImpact(),
                recommendation.evidenceIds());
    }
}
