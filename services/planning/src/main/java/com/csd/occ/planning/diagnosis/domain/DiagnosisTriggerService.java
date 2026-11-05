package com.csd.occ.planning.diagnosis.domain;

import com.csd.occ.planning.diagnosis.api.dto.DiagnosisRequestResponse;
import com.csd.occ.planning.diagnosis.api.dto.TriggerDiagnosisRequest;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiagnosisTriggerService {
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final RootCauseDiagnosisProcessor processor;
    private final RecommendationDeliveryService deliveries;
    private final RecommendationSubmissionPort recommendation;
    private final DiagnosisProperties properties;
    private final Clock clock;

    public DiagnosisTriggerService(
            DiagnosisRequestRepository requests,
            RootCauseDiagnosisRepository diagnoses,
            RootCauseDiagnosisProcessor processor,
            RecommendationDeliveryService deliveries,
            RecommendationSubmissionPort recommendation,
            DiagnosisProperties properties,
            Clock clock) {
        this.requests = requests;
        this.diagnoses = diagnoses;
        this.processor = processor;
        this.deliveries = deliveries;
        this.recommendation = recommendation;
        this.properties = properties;
        this.clock = clock;
    }

    public DiagnosisRequestResponse trigger(TriggerDiagnosisRequest body) {
        if (body == null || (body.eventId() == null && body.requestId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either eventId or requestId must be provided");
        }

        DiagnosisRequest request = findRequest(body);

        // If diagnosis already exists and completed, return existing view
        Optional<RootCauseDiagnosisEntity> existingResult = diagnoses.findByRequestId(request.id());
        if (request.status() == DiagnosisRequestStatus.COMPLETED && existingResult.isPresent()) {
            return toResponse(request, existingResult.get().id());
        }

        // Claim and process
        Instant now = clock.instant();
        request.claim(now.plus(properties.leaseDuration()), now);
        requests.save(request);

        processor.process(request.id());
        deliverDueRecommendations();

        DiagnosisRequest updated = requests.findById(request.id()).orElse(request);
        UUID diagnosisId = diagnoses.findByRequestId(updated.id())
                .map(RootCauseDiagnosisEntity::id)
                .orElse(null);
        return toResponse(updated, diagnosisId);
    }

    private DiagnosisRequest findRequest(TriggerDiagnosisRequest body) {
        if (body.requestId() != null) {
            return requests.findById(body.requestId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Diagnosis request not found for requestId " + body.requestId()));
        }
        return requests.findFirstByEventIdOrderByCreatedAtDesc(body.eventId())
                .or(() -> requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                        EventType.BUS_BUNCHING.name(), "disruption", body.eventId(),
                        DiagnosisRequestService.SCHEMA_VERSION))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Diagnosis request not found for event " + body.eventId()));
    }

    private void deliverDueRecommendations() {
        List<DeliveryClaim> batch = deliveries.claimDue(properties.workerBatchSize(), properties.leaseDuration());
        for (DeliveryClaim delivery : batch) {
            try {
                RecommendationSubmissionPort.DeliveryResult response = recommendation.submit(delivery.deliveryId(), delivery.payloadJson());
                if (response.httpStatus() == 200) {
                    deliveries.delivered(delivery.deliveryId(), response.httpStatus(), bounded(response.responseJson()));
                } else if (response.httpStatus() == 429 || response.httpStatus() == 408 || response.httpStatus() >= 500) {
                    deliveries.retry(delivery.deliveryId(), response.httpStatus(), "RECOMMENDATION_TRANSIENT_HTTP_FAILURE",
                            clock.instant().plus(properties.retryInitialDelay()));
                } else {
                    deliveries.terminal(delivery.deliveryId(), response.httpStatus(),
                            "RECOMMENDATION_REJECTED_HTTP_" + response.httpStatus());
                }
            } catch (RuntimeException failure) {
                deliveries.retry(delivery.deliveryId(), null, "RECOMMENDATION_UNAVAILABLE",
                        clock.instant().plus(properties.retryInitialDelay()));
            }
        }
    }

    private DiagnosisRequestResponse toResponse(DiagnosisRequest request, UUID diagnosisId) {
        return new DiagnosisRequestResponse(
                request.id(),
                diagnosisId,
                request.status(),
                request.failureCode(),
                request.createdAt(),
                request.policyOutcome());
    }

    private String bounded(String value) {
        if (value == null) return "";
        return value.length() > 10_000 ? value.substring(0, 10_000) : value;
    }
}
