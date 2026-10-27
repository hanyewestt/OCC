package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningScopeService {
    private final PlanningScopePort scopes;
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final SnapshotCodecPort codec;
    private final DiagnosisSummaryPolicy summaries = new DiagnosisSummaryPolicy();

    public PlanningScopeService(PlanningScopePort scopes, DiagnosisRequestRepository requests,
            RootCauseDiagnosisRepository diagnoses, SnapshotCodecPort codec) {
        this.scopes = scopes; this.requests = requests; this.diagnoses = diagnoses; this.codec = codec;
    }

    public PlanningScope current() { return scopes.current(); }

    @Transactional(readOnly = true)
    public boolean includesDiagnosis(UUID diagnosisId) {
        PlanningScope scope = current();
        return diagnoses.findById(diagnosisId).map(result -> decode(result.evidenceSnapshotJson()))
                .map(snapshot -> scope.includes(snapshot.event())).orElse(false);
    }

    @Transactional(readOnly = true)
    public ScopedDiagnosisPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid diagnosis page bounds");
        PlanningScope scope = current();
        var found = requests.findScoped(scope.classification(), scope.demoRunId() == null ? "" : scope.demoRunId().toString(),
                PageRequest.of(page, size));
        var pageItems = found.getContent();
        Map<UUID, RootCauseDiagnosisEntity> diagnosisByRequest = pageItems.isEmpty() ? Map.of()
                : diagnoses.findAllByRequestIdIn(pageItems.stream().map(DiagnosisRequest::id).toList()).stream()
                        .collect(Collectors.toMap(RootCauseDiagnosisEntity::requestId, Function.identity()));
        var items = pageItems.stream().map(request -> {
            var diagnosis = diagnosisByRequest.get(request.id());
            EvidenceSnapshot snapshot = decode(request.inputSnapshot());
            if (snapshot == null && diagnosis != null) snapshot = decode(diagnosis.evidenceSnapshotJson());
            var event = snapshot == null ? null : snapshot.event();
            var summary = diagnosis == null ? null : summaries.summarize(diagnosis.causeCode(), diagnosis.causeSubtype(),
                    decodeList(diagnosis.supportingEvidenceIdsJson()), decode(diagnosis.evidenceSnapshotJson()));
            return new PlanningDiagnosisSummary(request.id(), event == null ? request.eventId() : event.eventId(),
                    diagnosis == null ? null : diagnosis.id(), event == null ? scope.classification() : event.classification(),
                    event == null ? scope.demoRunId() : event.demoRunId(), event == null ? null : event.serviceId(),
                    event == null ? null : Integer.valueOf(event.directionId()), event == null ? null : event.monitoredStopCode(),
                    request.status(), summary == null ? null : summary.likelyCause(),
                    diagnosis == null ? null : diagnosis.confidence(), diagnosis == null ? List.of()
                            : decodeLimitations(diagnosis.limitationsJson()),
                    summary == null ? List.of() : summary.evidenceDetails(), request.failureCode(),
                    request.policyOutcome(), request.createdAt());
        }).toList();
        return new ScopedDiagnosisPage(scope.mode(), scope.demoRunId(), items, page, size, found.getTotalElements(), found.getTotalPages());
    }

    private EvidenceSnapshot decode(String value) {
        if (value == null || value.isBlank()) return null;
        try { return codec.decode(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private List<String> decodeList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return codec.decodeStringList(value).stream().filter(java.util.Objects::nonNull).limit(100).toList(); }
        catch (RuntimeException ignored) { return List.of(); }
    }

    private List<String> decodeLimitations(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return codec.decodeStringList(value).stream().filter(java.util.Objects::nonNull).limit(20).toList(); }
        catch (RuntimeException ignored) {
            return List.of("Evidence and limitation details are unavailable for this retained diagnosis.");
        }
    }

    @Transactional(readOnly = true)
    public ScopedHazardHistoryPage listHazardHistory(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid hazard history page bounds");
        PlanningScope scope = current();
        var found = requests.findTerminalHazardRequests(scope.classification(),
                scope.demoRunId() == null ? "" : scope.demoRunId().toString(), PageRequest.of(page, size));
        var items = found.map(request -> {
            var submission = codec.decodeProducerEvent(request.inputSnapshot());
            String title = submission.hazardSnapshot() != null && submission.hazardSnapshot().facts() != null
                    ? submission.hazardSnapshot().facts().title()
                    : submission.trafficSnapshot() != null ? submission.trafficSnapshot().message() : null;
            if (title == null || title.isBlank())
                title = request.eventType().replace('_', ' ') + " · " + submission.disruptionId();
            return new PlanningHazardHistorySummary(request.id(), request.eventId(),
                submission.disruptionId(), request.eventType(), title,
                request.status(), request.failureCode(), request.createdAt(), request.updatedAt());
        }).getContent();
        return new ScopedHazardHistoryPage(scope.mode(), scope.demoRunId(), items, page, size,
                found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ScopedHazardRequestPage listHazardRequests(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid hazard request page bounds");
        PlanningScope scope = current();
        var found = requests.findOpenHazardRequests(scope.classification(),
                scope.demoRunId() == null ? "" : scope.demoRunId().toString(), PageRequest.of(page, size));
        var items = found.map(request -> new PlanningHazardRequestSummary(request.id(), request.eventId(),
                codec.decodeProducerEvent(request.inputSnapshot()).disruptionId(), request.eventType(),
                request.status(), request.failureCode(), request.createdAt())).getContent();
        return new ScopedHazardRequestPage(scope.mode(), scope.demoRunId(), items, page, size,
                found.getTotalElements(), found.getTotalPages());
    }
}
