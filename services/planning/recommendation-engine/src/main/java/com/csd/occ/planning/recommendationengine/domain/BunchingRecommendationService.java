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
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");

        return mitigationContext.findFor(event)
                .flatMap(inputs -> policy.evaluate(
                        recommendationId,
                        event,
                        diagnosis,
                        inputs.context(),
                        inputs.impact()));
    }
}
