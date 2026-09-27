package com.csd.occ.diagnosis.infrastructure.recommendation;

import com.csd.occ.diagnosis.domain.RecommendationSubmissionPort;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpBunchingRecommendationAdapter implements RecommendationSubmissionPort {
    private final RestClient client;

    public HttpBunchingRecommendationAdapter(@Qualifier("recommendationRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public DeliveryResult submit(UUID idempotencyKey, String payloadJson) {
        return client.post().uri("/internal/v1/bunching-recommendations")
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("Content-Type", "application/json")
                .body(payloadJson)
                .exchange((request, response) -> new DeliveryResult(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}
