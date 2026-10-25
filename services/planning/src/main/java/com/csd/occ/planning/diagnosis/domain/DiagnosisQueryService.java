package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisQueryService {
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final RecommendationDeliveryRepository deliveries;
    private final SnapshotCodecPort codec;
    private final DiagnosisSummaryPolicy summaries = new DiagnosisSummaryPolicy();

    public DiagnosisQueryService(DiagnosisRequestRepository requests, RootCauseDiagnosisRepository diagnoses,
                                 RecommendationDeliveryRepository deliveries, SnapshotCodecPort codec) {
        this.requests = requests; this.diagnoses = diagnoses; this.deliveries = deliveries; this.codec = codec;
    }

    @Transactional(readOnly = true)
    public DiagnosisView find(UUID diagnosisId) {
        RootCauseDiagnosisEntity diagnosis = diagnoses.findById(diagnosisId).orElseThrow(DiagnosisNotFoundException::new);
        DiagnosisRequest request = requests.findById(diagnosis.requestId()).orElseThrow(DiagnosisNotFoundException::new);
        RecommendationDelivery delivery = deliveries.findByDiagnosisId(diagnosis.id()).orElse(null);
        var summary = summary(diagnosis);
        return new DiagnosisView(diagnosis.id(), request.status(), request.failureCode(), diagnosis.causeCode(),
                diagnosis.causeSubtype(), summary.likelyCause(), diagnosis.confidence(),
                diagnosis.evidenceSupportScore(), decodeList(diagnosis.supportingEvidenceIdsJson()),
                decodeList(diagnosis.limitationsJson()), diagnosis.completedAt(), request.eventType(),
                request.eventId(), request.producer(),
                delivery == null ? null : delivery.status(), delivery == null ? null : delivery.failureCode(),
                summary.evidenceDetails());
    }

    @Transactional(readOnly = true)
    public Map<UUID, DiagnosisSummaryPolicy.Summary> summariesByDiagnosisIds(Collection<UUID> diagnosisIds) {
        List<UUID> ids = diagnosisIds == null ? List.of() : diagnosisIds.stream().filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, DiagnosisSummaryPolicy.Summary> result = new LinkedHashMap<>();
        diagnoses.findAllById(ids).forEach(diagnosis -> result.put(diagnosis.id(), summary(diagnosis)));
        ids.forEach(id -> result.putIfAbsent(id, summaries.summarize(null, null, List.of(), null)));
        return Map.copyOf(result);
    }

    DiagnosisSummaryPolicy.Summary summary(RootCauseDiagnosisEntity diagnosis) {
        try {
            return summaries.summarize(diagnosis.causeCode(), diagnosis.causeSubtype(),
                    decodeList(diagnosis.supportingEvidenceIdsJson()), decodeSnapshot(diagnosis.evidenceSnapshotJson()));
        } catch (RuntimeException ignored) {
            return summaries.summarize(null, null, List.of(), null);
        }
    }

    private EvidenceSnapshot decodeSnapshot(String value) {
        if (value == null || value.isBlank()) return null;
        try { return codec.decode(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private List<String> decodeList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return codec.decodeStringList(value).stream().filter(java.util.Objects::nonNull).limit(100).toList(); }
        catch (RuntimeException ignored) { return List.of(); }
    }

    @Transactional(readOnly = true)
    public DiagnosisRequestView findRequest(UUID id) {
        DiagnosisRequest request = requests.findById(id).orElseThrow(DiagnosisNotFoundException::new);
        return requestView(request);
    }

    @Transactional(readOnly = true)
    public DiagnosisRequestView findRequestByEvent(UUID eventId, EventType eventType) {
        DiagnosisRequest request = requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                eventType.name(), "disruption", eventId, DiagnosisRequestService.SCHEMA_VERSION)
                .orElseThrow(DiagnosisNotFoundException::new);
        return requestView(request);
    }

    private DiagnosisRequestView requestView(DiagnosisRequest request) {
        UUID diagnosisId = diagnoses.findByRequestId(request.id()).map(RootCauseDiagnosisEntity::id)
                .orElse(null);
        return new DiagnosisRequestView(request.id(), diagnosisId, request.status(),
                request.failureCode(), request.createdAt(), request.policyOutcome());
    }

    public static final class DiagnosisNotFoundException extends RuntimeException { }
}
