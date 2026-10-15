package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisQueryService {
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final RecommendationDeliveryRepository deliveries;
    private final SnapshotCodecPort codec;

    public DiagnosisQueryService(DiagnosisRequestRepository requests, RootCauseDiagnosisRepository diagnoses,
                                 RecommendationDeliveryRepository deliveries, SnapshotCodecPort codec) {
        this.requests = requests; this.diagnoses = diagnoses; this.deliveries = deliveries; this.codec = codec;
    }

    @Transactional(readOnly = true)
    public DiagnosisView find(UUID diagnosisId) {
        RootCauseDiagnosisEntity diagnosis = diagnoses.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
        DiagnosisRequest request = requests.findById(diagnosis.requestId()).orElseThrow(DiagnosisNotFoundException::new);
        RecommendationDelivery delivery = deliveries.findByDiagnosisId(diagnosis.id()).orElse(null);
        return new DiagnosisView(diagnosis.id(), request.status(), request.failureCode(), diagnosis.causeCode(),
                diagnosis.causeSubtype(), diagnosis.likelyCause(), diagnosis.confidence(),
                diagnosis.evidenceSupportScore(), codec.decodeStringList(diagnosis.supportingEvidenceIdsJson()),
                codec.decodeStringList(diagnosis.limitationsJson()), diagnosis.completedAt(), request.eventType(),
                request.eventId(), request.producer(),
                delivery == null ? null : delivery.status(), delivery == null ? null : delivery.failureCode());
    }

    @Transactional(readOnly = true)
    public DiagnosisRequestView findRequest(UUID id) {
        DiagnosisRequest request = requests.findById(id).orElseThrow(DiagnosisNotFoundException::new);
        UUID diagnosisId = diagnoses.findByRequestId(request.id()).map(RootCauseDiagnosisEntity::id)
                .orElse(null);
        return new DiagnosisRequestView(request.id(), diagnosisId, request.status(),
                request.failureCode(), request.createdAt(), request.policyOutcome());
    }

    public static final class DiagnosisNotFoundException extends RuntimeException { }
}
