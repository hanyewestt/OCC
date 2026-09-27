package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;

public final class BunchingMitigationCandidateValidator {

    private static final int ABSOLUTE_ADJUSTMENT_LIMIT_SECONDS = 120;

    public MitigationCandidateValidationResult validate(
            MitigationCandidateRequest request,
            MitigationCandidate candidate) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(candidate, "candidate is required");

        ConfirmedBunchingEvent event = request.bunchingEvent();
        ValidatedMitigationContext context = request.mitigationContext();

        if (request.classification() != SourceClassification.SIMULATION) {
            return rejected(candidate, MitigationCandidateRejectionReason.NON_ACTIONABLE_CLASSIFICATION);
        }
        if (event.status() != BunchingEventStatus.CONFIRMED) {
            return rejected(candidate, MitigationCandidateRejectionReason.EVENT_NOT_CONFIRMED);
        }
        if (context.validationStatus() != MitigationValidationStatus.VALIDATED) {
            return rejected(candidate, MitigationCandidateRejectionReason.OPERATIONAL_CONTEXT_NOT_VALIDATED);
        }
        if (context.crowdSafetyStatus() != CrowdSafetyStatus.PASSED) {
            return rejected(candidate, MitigationCandidateRejectionReason.CROWD_SAFETY_NOT_PASSED);
        }
        if (!candidate.targetBusReference().equals(context.followingBusReference())) {
            return rejected(candidate, MitigationCandidateRejectionReason.TARGET_BUS_NOT_APPROVED);
        }
        if (!candidate.controlStopCode().equals(context.approvedControlStopCode())) {
            return rejected(candidate, MitigationCandidateRejectionReason.CONTROL_STOP_NOT_APPROVED);
        }

        CandidateMitigationType permittedType = switch (context.followingBusState()) {
            case READY_TO_DEPART -> CandidateMitigationType.HOLD;
            case BOARDING_AT_CONTROL_STOP -> CandidateMitigationType.DWELL_ADJUSTMENT;
        };
        if (candidate.mitigationType() != permittedType) {
            return rejected(candidate, MitigationCandidateRejectionReason.MITIGATION_TYPE_NOT_ALLOWED);
        }

        int headwayDeficitSeconds = event.expectedHeadwaySeconds()
                - event.observedHeadwaySeconds().getLast();
        if (headwayDeficitSeconds < 1) {
            return rejected(candidate, MitigationCandidateRejectionReason.NO_POSITIVE_HEADWAY_DEFICIT);
        }

        int operationalLimitSeconds = switch (candidate.mitigationType()) {
            case HOLD -> context.maximumHoldSeconds();
            case DWELL_ADJUSTMENT -> context.maximumDwellAdjustmentSeconds();
        };
        int maximumAllowedAdjustmentSeconds = Math.min(
                headwayDeficitSeconds,
                Math.min(operationalLimitSeconds, ABSOLUTE_ADJUSTMENT_LIMIT_SECONDS));
        if (candidate.requestedAdjustmentSeconds() > maximumAllowedAdjustmentSeconds) {
            return rejected(candidate, MitigationCandidateRejectionReason.ADJUSTMENT_EXCEEDS_LIMIT);
        }

        return new MitigationCandidateValidationResult.Accepted(
                candidate,
                maximumAllowedAdjustmentSeconds);
    }

    private MitigationCandidateValidationResult.Rejected rejected(
            MitigationCandidate candidate,
            MitigationCandidateRejectionReason reason) {
        return new MitigationCandidateValidationResult.Rejected(candidate, reason);
    }
}
