package com.csd.occ.planning.recommendation.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.csd.occ.planning.domain.audit.ApplicationAuditCapturePort;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableBunchingRecommendationService {

    private final BunchingRecommendationService recommendations;
    private final RecommendationRecordPort records;
    private final ModelInvocationRecordPort modelInvocations;
    private final Clock clock;
    private final ApplicationAuditCapturePort auditCapture;
    private final ApplicationAuditEventRepository auditEvents;

    public DurableBunchingRecommendationService(
            BunchingRecommendationService recommendations,
            RecommendationRecordPort records,
            ModelInvocationRecordPort modelInvocations,
            Clock clock,
            ApplicationAuditEventRepository auditEvents,
            ApplicationAuditCapturePort auditCapture) {
        this.recommendations = recommendations;
        this.records = records;
        this.modelInvocations = modelInvocations;
        this.clock = clock;
        this.auditEvents = auditEvents;
        this.auditCapture = auditCapture;
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
        modelInvocation.ifPresent(invocation -> {
            modelInvocations.save(invocation);
            recordAiAttempt(candidate, invocation);
        });
        if (records.saveIfAbsent(candidate)) {
            modelInvocations.attach(candidate.idempotencyKey());
            if (modelInvocation.isEmpty()) recordRecommendation(candidate);
            return candidate;
        }
        RecommendationRecord existing = records.findByIdempotencyKey(candidate.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Concurrent recommendation record was not visible after idempotency conflict"));
        modelInvocations.attach(existing.idempotencyKey());
        return requireMatchingRequest(existing, candidate.requestHash());
    }

    private void recordAiAttempt(RecommendationRecord candidate, ModelInvocationRecord invocation) {
        ModelInvocationMetadata metadata = invocation.metadata();
        ModelTokenUsage usage = metadata.tokenUsage().orElse(null);
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("provider", metadata.provider().name()); details.put("model", metadata.model());
        details.put("promptVersion", metadata.promptVersion()); details.put("schemaVersion", metadata.outputSchemaVersion());
        details.put("startedAt", metadata.startedAt().toString()); details.put("completedAt", metadata.completedAt().toString());
        details.put("latencyMillis", metadata.latencyMilliseconds());
        details.put("promptTokens", usage == null ? null : usage.promptTokens().orElse(null));
        details.put("completionTokens", usage == null ? null : usage.completionTokens().orElse(null));
        details.put("totalTokens", usage == null ? null : usage.totalTokens().orElse(null));
        details.put("outcome", invocation.outcome().name());
        details.put("fallbackUsed", invocation.deterministicFallbackUsed());
        details.put("failureCategory", invocation.failureCategory().map(Enum::name).orElse(null));
        details.put("input", metadata.inputSnapshot()); details.put("output", metadata.generatedResponse());
        auditCapture.recordRecommendationAttempt(metadata.invocationId(), metadata.completedAt(),
                candidate.idempotencyKey(), candidate.requestId(), invocation.outcome().name(), details);
    }

    private void recordRecommendation(RecommendationRecord candidate) {
        UUID eventId = UUID.randomUUID(); Instant now = Instant.now(clock);
        auditEvents.save(new ApplicationAuditEvent(eventId, candidate.createdAt(), now, "AI_PROCESSING",
                "RECOMMENDATION_GENERATED", "SUCCESS", null, "SYSTEM", "RECOMMENDATION",
                candidate.idempotencyKey().toString(), null, candidate.requestId().toString(),
                "Recommendation generated without a model invocation", java.util.Map.of("modelAttempted", false),
                "recommendation-generated:" + candidate.idempotencyKey()));
    }

    private RecommendationRecord requireMatchingRequest(RecommendationRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(existing.idempotencyKey());
        }
        return existing;
    }
}
