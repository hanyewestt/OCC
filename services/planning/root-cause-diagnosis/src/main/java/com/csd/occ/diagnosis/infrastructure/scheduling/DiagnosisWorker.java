package com.csd.occ.diagnosis.infrastructure.scheduling;

import com.csd.occ.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.diagnosis.domain.DeliveryClaim;
import com.csd.occ.diagnosis.domain.RecommendationDeliveryService;
import com.csd.occ.diagnosis.domain.RecommendationSubmissionPort;
import com.csd.occ.diagnosis.domain.RootCauseDiagnosisProcessor;
import com.csd.occ.diagnosis.infrastructure.config.DiagnosisProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "occ.diagnosis", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class DiagnosisWorker {
    private final DiagnosisRequestService requests;
    private final RootCauseDiagnosisProcessor processor;
    private final RecommendationDeliveryService deliveries;
    private final RecommendationSubmissionPort recommendation;
    private final DiagnosisProperties properties;
    private final Clock clock;

    public DiagnosisWorker(DiagnosisRequestService requests, RootCauseDiagnosisProcessor processor,
            RecommendationDeliveryService deliveries, RecommendationSubmissionPort recommendation,
            DiagnosisProperties properties, Clock clock) {
        this.requests = requests; this.processor = processor; this.deliveries = deliveries;
        this.recommendation = recommendation; this.properties = properties; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${occ.diagnosis.worker-interval}")
    public void processDueWork() {
        // AI calls can outlive the queue lease; claim one diagnosis at a time to avoid duplicate model work.
        List<java.util.UUID> requestIds = requests.claimDueRequests(1, properties.leaseDuration());
        requestIds.forEach(processor::process);
        deliverDueRecommendations();
    }

    public void deliverDueRecommendations() {
        List<DeliveryClaim> batch = deliveries.claimDue(properties.workerBatchSize(), properties.leaseDuration());
        for (DeliveryClaim delivery : batch) {
            try {
                RecommendationSubmissionPort.DeliveryResult response = recommendation.submit(delivery.deliveryId(), delivery.payloadJson());
                if (response.httpStatus() == 200) {
                    deliveries.delivered(delivery.deliveryId(), response.httpStatus(), bounded(response.responseJson()));
                } else if (response.httpStatus() == 429 || response.httpStatus() == 408 || response.httpStatus() >= 500) {
                    retryOrFail(delivery, response.httpStatus(), "RECOMMENDATION_TRANSIENT_HTTP_FAILURE");
                } else {
                    deliveries.terminal(delivery.deliveryId(), response.httpStatus(),
                            "RECOMMENDATION_REJECTED_HTTP_" + response.httpStatus());
                }
            } catch (RuntimeException failure) {
                retryOrFail(delivery, null, "RECOMMENDATION_UNAVAILABLE");
            }
        }
    }

    private void retryOrFail(DeliveryClaim delivery, Integer status, String code) {
        if (properties.deliveryMaxAttempts() > 0 && delivery.attemptNumber() >= properties.deliveryMaxAttempts()) {
            deliveries.terminal(delivery.deliveryId(), status, code);
            return;
        }
        long exponent = Math.min(20, Math.max(0, delivery.attemptNumber() - 1));
        long base = Math.min(properties.retryMaxDelay().toMillis(),
                properties.retryInitialDelay().toMillis() * (1L << exponent));
        long jitter = base == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, base / 5 + 1));
        deliveries.retry(delivery.deliveryId(), status, code,
                clock.instant().plusMillis(Math.min(properties.retryMaxDelay().toMillis(), base + jitter)));
    }

    private String bounded(String value) {
        if (value == null) return "";
        return value.length() > 10_000 ? value.substring(0, 10_000) : value;
    }
}
