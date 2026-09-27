package com.csd.occ.planning.recommendationengine.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableBunchingRecommendationService {

    private final BunchingRecommendationService recommendations;
    private final RecommendationRecordPort records;
    private final ModelInvocationRecordPort modelInvocations;
    private final Clock clock;

    public DurableBunchingRecommendationService(
            BunchingRecommendationService recommendations,
            RecommendationRecordPort records,
            ModelInvocationRecordPort modelInvocations,
            Clock clock) {
        this.recommendations = recommendations;
        this.records = records;
        this.modelInvocations = modelInvocations;
        this.clock = clock;
    }

    @Transactional
    public Optional<RecommendationRecord> recommend(
            UUID idempotencyKey,
            String requestHash,
            UUID requestId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(requestHash, "requestHash is required");
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");

        Optional<RecommendationRecord> existing = records.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return Optional.of(requireMatchingRequest(existing.orElseThrow(), requestHash));
        }

        return recommendations.evaluate(idempotencyKey, classification, event, diagnosis)
                .map(decision -> persistOrReplay(
                        new RecommendationRecord(
                                idempotencyKey,
                                requestHash,
                                requestId,
                                classification,
                                event,
                                diagnosis,
                                decision.mitigationContext(),
                                decision.recommendation(),
                                Instant.now(clock)),
                        decision.modelInvocation()));
    }

    private RecommendationRecord persistOrReplay(
            RecommendationRecord candidate,
            Optional<ModelInvocationRecord> modelInvocation) {
        if (records.saveIfAbsent(candidate)) {
            modelInvocation.ifPresent(modelInvocations::save);
            return candidate;
        }
        RecommendationRecord existing = records.findByIdempotencyKey(candidate.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Concurrent recommendation record was not visible after idempotency conflict"));
        return requireMatchingRequest(existing, candidate.requestHash());
    }

    private RecommendationRecord requireMatchingRequest(RecommendationRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(existing.idempotencyKey());
        }
        return existing;
    }
}
