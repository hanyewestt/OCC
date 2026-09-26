package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BunchingRecommendationService {

    private final BunchingMitigationContextPort mitigationContext;
    private final BunchingRecommendationPolicy policy;

    public BunchingRecommendationService(
            BunchingMitigationContextPort mitigationContext,
            BunchingRecommendationPolicy policy) {
        this.mitigationContext = mitigationContext;
        this.policy = policy;
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
                .flatMap(inputs -> policy.evaluate(
                        recommendationId,
                        classification,
                        event,
                        diagnosis,
                        inputs.context(),
                        inputs.impact())
                        .map(recommendation -> new BunchingRecommendationDecision(
                                recommendation,
                                inputs.context())));
    }
}
