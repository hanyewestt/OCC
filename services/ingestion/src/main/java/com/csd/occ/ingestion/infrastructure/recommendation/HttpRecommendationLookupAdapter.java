package com.csd.occ.ingestion.infrastructure.recommendation;

import com.csd.occ.ingestion.domain.DemoRecommendationDetails;
import com.csd.occ.ingestion.domain.DemoRecommendationImpact;
import com.csd.occ.ingestion.domain.DemoRecommendationUnavailableException;
import com.csd.occ.ingestion.domain.RecommendationLookupPort;
import com.csd.occ.ingestion.infrastructure.config.DemoProperties;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpRecommendationLookupAdapter implements RecommendationLookupPort {
    private final RestClient recommendations;

    public HttpRecommendationLookupAdapter(DemoProperties settings) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.recommendationTimeout()).build());
        requests.setReadTimeout(settings.recommendationTimeout());
        recommendations = RestClient.builder().baseUrl(settings.recommendationInternalUrl().toString())
                .requestFactory(requests).build();
    }

    @Override
    public Optional<DemoRecommendationDetails> findByBunchingEventId(UUID bunchingEventId) {
        try {
            RecommendationResponse response = recommendations.get()
                    .uri("/internal/v1/bunching-recommendations/by-bunching-event/{bunchingEventId}", bunchingEventId)
                    .retrieve().body(RecommendationResponse.class);
            return response == null ? Optional.empty() : Optional.of(new DemoRecommendationDetails(
                    response.recommendationId(), response.classification(), response.status(), response.mitigationType(),
                    response.targetBusReference(), response.controlStopCode(), response.adjustmentSeconds(),
                    response.explanation().rationale(), response.explanation().likelyCause(),
                    response.explanation().diagnosisConfidence(), response.explanation().tradeOffs(),
                    response.explanation().limitations(), new DemoRecommendationImpact(
                            response.expectedImpact().headwayBeforeSeconds(), response.expectedImpact().headwayAfterSeconds(),
                            response.expectedImpact().queueBeforePassengers(), response.expectedImpact().queueAfterPassengers(),
                            response.expectedImpact().passengerMinutesBefore(), response.expectedImpact().passengerMinutesAfter())));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return Optional.empty();
            throw new DemoRecommendationUnavailableException();
        } catch (RuntimeException exception) {
            throw new DemoRecommendationUnavailableException();
        }
    }

    private record RecommendationResponse(UUID recommendationId, String classification, String status,
                                          String mitigationType, String targetBusReference, String controlStopCode,
                                          int adjustmentSeconds, Explanation explanation, Impact expectedImpact) {}
    private record Explanation(String rationale, String likelyCause, BigDecimal diagnosisConfidence,
                               List<String> tradeOffs, List<String> limitations) {}
    private record Impact(int headwayBeforeSeconds, int headwayAfterSeconds, int queueBeforePassengers,
                          int queueAfterPassengers, BigDecimal passengerMinutesBefore,
                          BigDecimal passengerMinutesAfter) {}
}
