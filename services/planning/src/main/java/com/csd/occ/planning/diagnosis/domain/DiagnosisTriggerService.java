package com.csd.occ.planning.diagnosis.domain;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisTriggerService {
    private final DiagnosisRequestRepository requests;
    private final RootCauseDiagnosisRepository diagnoses;
    private final Clock clock;

    public DiagnosisTriggerService(DiagnosisRequestRepository requests, RootCauseDiagnosisRepository diagnoses,
            Clock clock) {
        this.requests = requests;
        this.diagnoses = diagnoses;
        this.clock = clock;
    }

    @Transactional
    public DiagnosisRequestView trigger(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId is required");
        return queue(requests.findByIdForUpdate(requestId)
                .orElseThrow(DiagnosisQueryService.DiagnosisNotFoundException::new));
    }

    @Transactional
    public DiagnosisRequestView triggerByEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId is required");
        return queue(requests.findFirstByEventIdOrderByCreatedAtDesc(eventId)
                .orElseThrow(DiagnosisQueryService.DiagnosisNotFoundException::new));
    }

    private DiagnosisRequestView queue(DiagnosisRequest request) {
        request.queue(clock.instant());
        UUID diagnosisId = diagnoses.findByRequestId(request.id()).map(RootCauseDiagnosisEntity::id).orElse(null);
        return new DiagnosisRequestView(request.id(), diagnosisId, request.status(), request.failureCode(),
                request.createdAt(), request.policyOutcome());
    }
}
