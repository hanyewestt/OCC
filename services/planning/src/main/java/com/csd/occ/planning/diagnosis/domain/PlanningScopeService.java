package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningScopeService {
    private final PlanningScopePort scopes;
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final SnapshotCodecPort codec;

    public PlanningScopeService(PlanningScopePort scopes, DiagnosisRequestRepository requests,
            RootCauseDiagnosisRepository diagnoses, SnapshotCodecPort codec) {
        this.scopes = scopes; this.requests = requests; this.diagnoses = diagnoses; this.codec = codec;
    }

    public PlanningScope current() { return scopes.current(); }

    @Transactional(readOnly = true)
    public boolean includesDiagnosis(UUID diagnosisId) {
        PlanningScope scope = current();
        return diagnoses.findById(diagnosisId)
                .map(result -> scope.includes(codec.decode(result.evidenceSnapshotJson()).event())).orElse(false);
    }

    @Transactional(readOnly = true)
    public ScopedDiagnosisPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid diagnosis page bounds");
        PlanningScope scope = current();
        var found = requests.findScoped(scope.classification(), scope.demoRunId() == null ? "" : scope.demoRunId().toString(),
                PageRequest.of(page, size));
        var items = found.map(request -> {
            var event = codec.decode(request.inputSnapshot()).event();
            var diagnosis = diagnoses.findByRequestId(request.id()).orElse(null);
            return new PlanningDiagnosisSummary(request.id(), event.eventId(), diagnosis == null ? null : diagnosis.id(),
                    event.classification(), event.demoRunId(), event.serviceId(), event.directionId(), event.monitoredStopCode(),
                    request.status(), diagnosis == null ? null : diagnosis.likelyCause(), diagnosis == null ? null : diagnosis.confidence(),
                    diagnosis == null ? java.util.List.of() : codec.decodeStringList(diagnosis.limitationsJson()),
                    request.failureCode(), request.policyOutcome(), request.createdAt());
        }).getContent();
        return new ScopedDiagnosisPage(scope.mode(), scope.demoRunId(), items, page, size, found.getTotalElements(), found.getTotalPages());
    }
}
