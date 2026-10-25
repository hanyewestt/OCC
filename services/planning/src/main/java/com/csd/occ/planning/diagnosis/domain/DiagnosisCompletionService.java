package com.csd.occ.planning.diagnosis.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    public DiagnosisCompletionService(DiagnosisRequestRepository requests, RootCauseDiagnosisRepository diagnoses,
            ModelInvocationRecordRepository invocations, RecommendationDeliveryRepository deliveries,
            SnapshotCodecPort codec, Clock clock) {
        this.requests = requests; this.diagnoses = diagnoses; this.invocations = invocations;
        this.deliveries = deliveries; this.codec = codec; this.clock = clock;
    }

    @Transactional
    public void complete(UUID requestId, RootCauseDiagnosis diagnosis, List<ModelInvocationDetails> invocationDetails,
                         String snapshotHash, String deliveryPayload, UUID deliveryId) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        diagnoses.save(new RootCauseDiagnosisEntity(diagnosis, codec.encodeStringList(diagnosis.supportingEvidenceIds()),
                codec.encodeStringList(diagnosis.limitations()), diagnosis.confidenceComponents() == null ? null
                        : codec.encodeConfidenceComponents(diagnosis.confidenceComponents())));
        invocationDetails.stream().map(details -> new ModelInvocationRecord(UUID.randomUUID(), requestId,
                details, snapshotHash)).forEach(invocations::save);
        deliveries.save(new RecommendationDelivery(deliveryId, diagnosis.id(), deliveryPayload,
                snapshotHash(deliveryPayload), clock.instant()));
        request.complete(clock.instant());
    }

    @Transactional
    public void completeAdvisory(UUID requestId, RootCauseDiagnosis diagnosis,
            List<ModelInvocationDetails> invocationDetails, String snapshotHash) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        diagnoses.save(new RootCauseDiagnosisEntity(diagnosis, codec.encodeStringList(diagnosis.supportingEvidenceIds()),
                codec.encodeStringList(diagnosis.limitations()), diagnosis.confidenceComponents() == null ? null
                        : codec.encodeConfidenceComponents(diagnosis.confidenceComponents())));
        invocationDetails.stream().map(details -> new ModelInvocationRecord(UUID.randomUUID(), requestId,
                details, snapshotHash)).forEach(invocations::save);
        request.completeNoAction("LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT", clock.instant());
    }

    @Transactional
    public void fail(UUID requestId, String failureCode, boolean retryable, boolean attemptsRemaining,
                     Instant retryAt, List<ModelInvocationDetails> invocationDetails, String snapshotHash) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        invocationDetails.stream().map(details -> new ModelInvocationRecord(
                UUID.randomUUID(), requestId, details, snapshotHash)).forEach(invocations::save);
        if (retryable && attemptsRemaining) request.failRetryable(failureCode, retryAt, clock.instant());
        else request.failTerminal(failureCode, clock.instant());
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
