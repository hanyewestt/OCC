package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BunchingRecommendationService {

    private final BunchingMitigationContextPort mitigationContext;
    private final BunchingRecommendationOrchestrator orchestrator;

    public BunchingRecommendationService(
            BunchingMitigationContextPort mitigationContext,
            BunchingRecommendationOrchestrator orchestrator) {
        this.mitigationContext = mitigationContext;
        this.orchestrator = orchestrator;
    }

    public Optional<ValidatedMitigationRecommendation> recommend(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");

        return evaluate(recommendationId, classification, event, diagnosis)
                .map(BunchingRecommendationDecision::recommendation);
    }

    public Optional<BunchingRecommendationDecision> evaluate(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");

        return mitigationContext.findFor(event)
                .flatMap(inputs -> orchestrator.evaluate(
                        recommendationId,
                        classification,
                        event,
                        diagnosis,
                        inputs.context(),
                        inputs.impact())
                        .map(outcome -> new BunchingRecommendationDecision(
                                outcome.recommendation(),
                                inputs.context(),
                                outcome.modelInvocation())));
    }
}
