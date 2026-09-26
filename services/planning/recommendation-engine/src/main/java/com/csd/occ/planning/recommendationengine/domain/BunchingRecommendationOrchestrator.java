package com.csd.occ.planning.recommendationengine.domain;

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
            return deterministicRecommendation;
        }

        MitigationCandidateRequest candidateRequest = new MitigationCandidateRequest(
                classification,
                event,
                diagnosis,
                context);
        Optional<MitigationCandidate> generatedCandidate = generateCandidate(candidateRequest);
        if (generatedCandidate.isEmpty()) {
            return deterministicRecommendation;
        }

        MitigationCandidate candidate = generatedCandidate.orElseThrow();
        MitigationCandidateValidationResult validation = candidateValidator.validate(candidateRequest, candidate);
        ValidatedMitigationRecommendation fallback = deterministicRecommendation.orElseThrow();
        if (!(validation instanceof MitigationCandidateValidationResult.Accepted)
                || !matchesDeterministicAction(candidate, fallback)) {
            return deterministicRecommendation;
        }

        return Optional.of(withCandidateRationale(fallback, candidate.rationale()));
    }

    private Optional<MitigationCandidate> generateCandidate(MitigationCandidateRequest request) {
        try {
            return Objects.requireNonNull(
                    candidateGenerator.orElseThrow().generate(request),
                    "candidate generator returned null");
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
