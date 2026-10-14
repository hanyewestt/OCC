package com.csd.occ.planning.diagnosis.infrastructure.recommendation;

import com.csd.occ.planning.diagnosis.domain.RecommendationSubmissionPort;
import com.csd.occ.planning.recommendation.api.RecommendationRequestFingerprint;
import com.csd.occ.planning.recommendation.api.dto.BunchingRecommendationResponse;
import com.csd.occ.planning.recommendation.api.dto.CreateBunchingRecommendationRequest;
import com.csd.occ.planning.recommendation.domain.DurableBunchingRecommendationService;
import com.csd.occ.planning.recommendation.domain.IdempotencyConflictException;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PlanningRecommendationAdapter implements RecommendationSubmissionPort {
    private final JsonMapper json;
    private final Validator validator;
    private final DurableBunchingRecommendationService recommendations;
    private final RecommendationRequestFingerprint fingerprints;

    public PlanningRecommendationAdapter(JsonMapper json, Validator validator,
                                         DurableBunchingRecommendationService recommendations,
                                         RecommendationRequestFingerprint fingerprints) {
        this.json = json;
        this.validator = validator;
        this.recommendations = recommendations;
        this.fingerprints = fingerprints;
    }

    @Override
    public DeliveryResult submit(UUID idempotencyKey, String payloadJson) {
        CreateBunchingRecommendationRequest request;
        try {
            request = json.readValue(payloadJson, CreateBunchingRecommendationRequest.class);
        } catch (JacksonException malformedPayload) {
            return new DeliveryResult(400, "");
        }
        if (!validator.validate(request).isEmpty()) return new DeliveryResult(400, "");

        try {
            var recommendation = recommendations.recommend(
                    idempotencyKey,
                    fingerprints.fingerprint(request),
                    request.requestId(),
                    request.classification(),
                    request.bunchingEvent().toDomain(),
                    request.diagnosis().toDomain());
            if (recommendation.isEmpty()) return new DeliveryResult(422, "");
            var record = recommendation.orElseThrow();
            String response = json.writeValueAsString(BunchingRecommendationResponse.from(
                    record.requestId(), record.classification(), record.recommendation()));
            return new DeliveryResult(200, response);
        } catch (IdempotencyConflictException conflict) {
            return new DeliveryResult(409, "");
        }
    }
}
