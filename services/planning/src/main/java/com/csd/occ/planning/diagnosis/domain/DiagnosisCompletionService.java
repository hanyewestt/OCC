package com.csd.occ.planning.diagnosis.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisCompletionService {
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final ModelInvocationRecordRepository invocations;
    private final RecommendationDeliveryRepository deliveries;
    private final SnapshotCodecPort codec;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;

    public DiagnosisCompletionService(DiagnosisRequestRepository requests, RootCauseDiagnosisRepository diagnoses,
            ModelInvocationRecordRepository invocations, RecommendationDeliveryRepository deliveries,
            SnapshotCodecPort codec, Clock clock, ApplicationAuditEventRepository auditEvents) {
        this.requests = requests; this.diagnoses = diagnoses; this.invocations = invocations;
        this.deliveries = deliveries; this.codec = codec; this.clock = clock; this.auditEvents = auditEvents;
    }

    @Transactional
    public void complete(UUID requestId, RootCauseDiagnosis diagnosis, List<ModelInvocationDetails> invocationDetails,
                         String snapshotHash, String deliveryPayload, UUID deliveryId) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        diagnoses.save(new RootCauseDiagnosisEntity(diagnosis, codec.encodeStringList(diagnosis.supportingEvidenceIds()),
                codec.encodeStringList(diagnosis.limitations()), diagnosis.confidenceComponents() == null ? null
                        : codec.encodeConfidenceComponents(diagnosis.confidenceComponents())));
        invocationDetails.forEach(details -> {
            ModelInvocationRecord record = new ModelInvocationRecord(UUID.randomUUID(), requestId, details, snapshotHash);
            invocations.save(record);
            recordAiAttempt(requestId, record.id(), details);
        });
        deliveries.save(new RecommendationDelivery(deliveryId, diagnosis.id(), deliveryPayload,
                snapshotHash(deliveryPayload), clock.instant()));
        request.complete(clock.instant());
        recordProcessingEvent(requestId, "DIAGNOSIS_COMPLETED", "SUCCESS", "Diagnosis completed");
    }

    @Transactional
    public void completeAdvisory(UUID requestId, RootCauseDiagnosis diagnosis,
            List<ModelInvocationDetails> invocationDetails, String snapshotHash) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        diagnoses.save(new RootCauseDiagnosisEntity(diagnosis, codec.encodeStringList(diagnosis.supportingEvidenceIds()),
                codec.encodeStringList(diagnosis.limitations()), diagnosis.confidenceComponents() == null ? null
                        : codec.encodeConfidenceComponents(diagnosis.confidenceComponents())));
        invocationDetails.forEach(details -> {
            ModelInvocationRecord record = new ModelInvocationRecord(UUID.randomUUID(), requestId, details, snapshotHash);
            invocations.save(record);
            recordAiAttempt(requestId, record.id(), details);
        });
        request.completeNoAction("LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT", clock.instant());
        recordProcessingEvent(requestId, "DIAGNOSIS_COMPLETED_WITHOUT_ACTION", "SUCCESS", "Diagnosis completed without an operational action");
    }

    @Transactional
    public void fail(UUID requestId, String failureCode, boolean retryable, boolean attemptsRemaining,
                     Instant retryAt, List<ModelInvocationDetails> invocationDetails, String snapshotHash) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        invocationDetails.forEach(details -> {
            ModelInvocationRecord record = new ModelInvocationRecord(UUID.randomUUID(), requestId, details, snapshotHash);
            invocations.save(record);
            recordAiAttempt(requestId, record.id(), details);
        });
        if (retryable && attemptsRemaining) request.failRetryable(failureCode, retryAt, clock.instant());
        else request.failTerminal(failureCode, clock.instant());
        recordProcessingEvent(requestId, "DIAGNOSIS_FAILED", "FAILURE", "Diagnosis processing failed");
    }

    private void recordAiAttempt(UUID requestId, UUID eventId, ModelInvocationDetails details) {
        var evidence = new java.util.LinkedHashMap<String, Object>();
        evidence.put("provider", details.provider()); evidence.put("model", details.model());
        evidence.put("promptVersion", details.promptVersion()); evidence.put("schemaVersion", details.schemaVersion());
        evidence.put("attempt", details.attemptNumber());
        evidence.put("startedAt", details.startedAt() == null ? null : details.startedAt().toString());
        evidence.put("completedAt", details.completedAt() == null ? null : details.completedAt().toString());
        evidence.put("promptTokens", details.promptTokens()); evidence.put("completionTokens", details.completionTokens());
        evidence.put("totalTokens", details.totalTokens()); evidence.put("validationOutcome", details.validationOutcome());
        evidence.put("failureCategory", details.failureCategory()); evidence.put("input", details.inputSnapshot());
        evidence.put("output", details.generatedResponse());
        java.time.Instant occurredAt = details.completedAt() == null ? clock.instant() : details.completedAt();
        auditEvents.save(new ApplicationAuditEvent(eventId, occurredAt, clock.instant(), "AI_PROCESSING",
                "AI_DIAGNOSIS_ATTEMPT", details.validationOutcome() == null ? "UNKNOWN" : details.validationOutcome(),
                null, "SYSTEM", "DIAGNOSIS_REQUEST", requestId.toString(), null, requestId.toString(),
                "Diagnosis model attempt recorded", evidence, "model-invocation:" + eventId));
    }

    private void recordProcessingEvent(UUID requestId, String type, String outcome, String summary) {
        UUID eventId = UUID.randomUUID(); java.time.Instant now = clock.instant();
        auditEvents.save(new ApplicationAuditEvent(eventId, now, now, "OPERATIONS", type, outcome, null,
                "SYSTEM", "DIAGNOSIS_REQUEST", requestId.toString(), null, requestId.toString(), summary,
                java.util.Map.of(), "diagnosis:" + eventId));
    }

    private String snapshotHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
