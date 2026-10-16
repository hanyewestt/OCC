package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingMitigationCandidateValidatorTest {

    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID DIAGNOSIS_ID = UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");

    private final BunchingMitigationCandidateValidator validator = new BunchingMitigationCandidateValidator();

    @Test
    void acceptsSafeHoldWithinEveryDeterministicLimit() {
        MitigationCandidate candidate = candidate(CandidateMitigationType.HOLD, "SBS5898U", "44241", 60);

        MitigationCandidateValidationResult result = validator.validate(
                request(
                        SourceClassification.SIMULATION,
                        event(BunchingEventStatus.CONFIRMED, 221),
                        context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                candidate);

        assertThat(result).isInstanceOfSatisfying(
                MitigationCandidateValidationResult.Accepted.class,
                accepted -> {
                    assertThat(accepted.candidate()).isSameAs(candidate);
                    assertThat(accepted.maximumAllowedAdjustmentSeconds()).isEqualTo(90);
                });
    }

    @Test
    void acceptsSafeDwellAdjustmentForBoardingBus() {
        MitigationCandidate candidate = candidate(
                CandidateMitigationType.DWELL_ADJUSTMENT,
                "SBS5898U",
                "44241",
                75);

        MitigationCandidateValidationResult result = validator.validate(
                request(
                        SourceClassification.SIMULATION,
                        event(BunchingEventStatus.CONFIRMED, 221),
                        context(FollowingBusState.BOARDING_AT_CONTROL_STOP, 90, 75,
                                CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                candidate);

        assertThat(result).isInstanceOfSatisfying(
                MitigationCandidateValidationResult.Accepted.class,
                accepted -> assertThat(accepted.maximumAllowedAdjustmentSeconds()).isEqualTo(75));
    }

    @Test
    void rejectsNonActionableClassificationAndUnconfirmedEvent() {
        MitigationCandidate candidate = candidate(CandidateMitigationType.HOLD, "SBS5898U", "44241", 60);
        ValidatedMitigationContext context = context(
                FollowingBusState.READY_TO_DEPART,
                90,
                75,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED);

        assertRejected(
                validator.validate(
                        request(SourceClassification.OBSERVATION_ONLY,
                                event(BunchingEventStatus.CONFIRMED, 221), context),
                        candidate),
                MitigationCandidateRejectionReason.NON_ACTIONABLE_CLASSIFICATION);
        assertRejected(
                validator.validate(
                        request(SourceClassification.SIMULATION,
                                event(BunchingEventStatus.INSUFFICIENT_EVIDENCE, 221), context),
                        candidate),
                MitigationCandidateRejectionReason.EVENT_NOT_CONFIRMED);
    }

    @Test
    void rejectsUnvalidatedOrCrowdUnsafeOperationalContext() {
        MitigationCandidate candidate = candidate(CandidateMitigationType.HOLD, "SBS5898U", "44241", 60);
        ConfirmedBunchingEvent event = event(BunchingEventStatus.CONFIRMED, 221);

        assertRejected(
                validator.validate(
                        request(SourceClassification.SIMULATION, event,
                                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.REJECTED)),
                        candidate),
                MitigationCandidateRejectionReason.OPERATIONAL_CONTEXT_NOT_VALIDATED);
        assertRejected(
                validator.validate(
                        request(SourceClassification.SIMULATION, event,
                                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                        CrowdSafetyStatus.FAILED, MitigationValidationStatus.VALIDATED)),
                        candidate),
                MitigationCandidateRejectionReason.CROWD_SAFETY_NOT_PASSED);
    }

    @Test
    void rejectsBusOrControlStopOutsideApprovedContext() {
        MitigationCandidateRequest request = request(
                SourceClassification.SIMULATION,
                event(BunchingEventStatus.CONFIRMED, 221),
                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED));

        assertRejected(
                validator.validate(
                        request,
                        candidate(CandidateMitigationType.HOLD, "UNAPPROVED-BUS", "44241", 60)),
                MitigationCandidateRejectionReason.TARGET_BUS_NOT_APPROVED);
        assertRejected(
                validator.validate(
                        request,
                        candidate(CandidateMitigationType.HOLD, "SBS5898U", "00000", 60)),
                MitigationCandidateRejectionReason.CONTROL_STOP_NOT_APPROVED);
    }

    @Test
    void rejectsMitigationThatDoesNotMatchFollowingBusState() {
        MitigationCandidate candidate = candidate(
                CandidateMitigationType.DWELL_ADJUSTMENT,
                "SBS5898U",
                "44241",
                60);

        assertRejected(
                validator.validate(
                        request(
                                SourceClassification.SIMULATION,
                                event(BunchingEventStatus.CONFIRMED, 221),
                                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                        candidate),
                MitigationCandidateRejectionReason.MITIGATION_TYPE_NOT_ALLOWED);
    }

    @Test
    void rejectsCandidateWhenThereIsNoPositiveHeadwayDeficit() {
        assertRejected(
                validator.validate(
                        request(
                                SourceClassification.SIMULATION,
                                event(BunchingEventStatus.CONFIRMED, 620),
                                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                        candidate(CandidateMitigationType.HOLD, "SBS5898U", "44241", 60)),
                MitigationCandidateRejectionReason.NO_POSITIVE_HEADWAY_DEFICIT);
    }

    @Test
    void rejectsAdjustmentAboveOperationalOrHeadwayLimit() {
        MitigationCandidate candidate = candidate(CandidateMitigationType.HOLD, "SBS5898U", "44241", 91);

        assertRejected(
                validator.validate(
                        request(
                                SourceClassification.SIMULATION,
                                event(BunchingEventStatus.CONFIRMED, 221),
                                context(FollowingBusState.READY_TO_DEPART, 90, 75,
                                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                        candidate),
                MitigationCandidateRejectionReason.ADJUSTMENT_EXCEEDS_LIMIT);
        assertRejected(
                validator.validate(
                        request(
                                SourceClassification.SIMULATION,
                                event(BunchingEventStatus.CONFIRMED, 550),
                                context(FollowingBusState.READY_TO_DEPART, 120, 75,
                                        CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED)),
                        candidate),
                MitigationCandidateRejectionReason.ADJUSTMENT_EXCEEDS_LIMIT);
    }

    private void assertRejected(
            MitigationCandidateValidationResult result,
            MitigationCandidateRejectionReason reason) {
        assertThat(result).isInstanceOfSatisfying(
                MitigationCandidateValidationResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo(reason));
    }

    private MitigationCandidateRequest request(
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            ValidatedMitigationContext context) {
        return new MitigationCandidateRequest(classification, event, diagnosis(), context);
    }

    private ConfirmedBunchingEvent event(BunchingEventStatus status, int latestHeadwaySeconds) {
        return new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44251",
                status,
                600,
                List.of(276, latestHeadwaySeconds),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of(
                        "route190-arrival-20260928T074402+0800",
                        "route190-arrival-20260928T074502+0800"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                DIAGNOSIS_ID,
                "A vehicle breakdown delayed the leader.",
                new BigDecimal("0.82"),
                List.of("route190-breakdown-delay-sbs8027y"),
                List.of("Synthetic test diagnosis."));
    }

    private ValidatedMitigationContext context(
            FollowingBusState state,
            int maximumHoldSeconds,
            int maximumDwellAdjustmentSeconds,
            CrowdSafetyStatus crowdSafety,
            MitigationValidationStatus validationStatus) {
        return new ValidatedMitigationContext(
                "SBS5898U",
                "44241",
                state,
                maximumHoldSeconds,
                maximumDwellAdjustmentSeconds,
                crowdSafety,
                validationStatus,
                List.of("route190-reference-directive-first-hold"));
    }

    private MitigationCandidate candidate(
            CandidateMitigationType mitigationType,
            String busReference,
            String controlStopCode,
            int adjustmentSeconds) {
        return new MitigationCandidate(
                mitigationType,
                busReference,
                controlStopCode,
                adjustmentSeconds,
                "Use only the approved operational context.");
    }
}
