package com.csd.occ.planning.recommendationengine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingRecommendationPolicyTest {

    private static final UUID RECOMMENDATION_ID = UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID DIAGNOSIS_ID = UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");

    private final BunchingRecommendationPolicy policy = new BunchingRecommendationPolicy();

    @Test
    void readyToDepartBusReceivesValidatedHold() {
        Optional<ValidatedMitigationRecommendation> result = policy.evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                confirmedEvent(),
                diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 90, 120),
                impact());

        assertThat(result).hasValueSatisfying(recommendation -> {
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(90);
            assertThat(recommendation.targetBusReference()).isEqualTo("SIM-BUS-190-02");
            assertThat(recommendation.controlStopCode()).isEqualTo("44539");
            assertThat(recommendation.evidenceIds())
                    .containsExactly("arrival-observation-101", "arrival-observation-102",
                            "diagnosis-evidence-001", "validation-001");
        });
    }

    @Test
    void boardingBusReceivesValidatedDwellAdjustment() {
        Optional<ValidatedMitigationRecommendation> result = policy.evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                confirmedEvent(),
                diagnosis(),
                validatedContext(FollowingBusState.BOARDING_AT_CONTROL_STOP, 90, 75),
                impact());

        assertThat(result).hasValueSatisfying(recommendation -> {
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.DWELL_ADJUSTMENT);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(75);
        });
    }

    @Test
    void unsafeCrowdConditionProducesNoAction() {
        ValidatedMitigationContext context = new ValidatedMitigationContext(
                "SIM-BUS-190-02",
                "44539",
                FollowingBusState.READY_TO_DEPART,
                90,
                120,
                CrowdSafetyStatus.FAILED,
                MitigationValidationStatus.VALIDATED,
                List.of("validation-001"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION,
                confirmedEvent(), diagnosis(), context, impact()))
                .isEmpty();
    }

    @Test
    void rejectedOperationalValidationProducesNoAction() {
        ValidatedMitigationContext context = new ValidatedMitigationContext(
                "SIM-BUS-190-02",
                "44539",
                FollowingBusState.READY_TO_DEPART,
                90,
                120,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.REJECTED,
                List.of("validation-001"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION,
                confirmedEvent(), diagnosis(), context, impact()))
                .isEmpty();
    }

    @Test
    void unconfirmedBunchingProducesNoAction() {
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44531",
                BunchingEventStatus.INSUFFICIENT_EVIDENCE,
                600,
                List.of(180, 210),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-101", "arrival-observation-102"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION, event, diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 90, 120), impact()))
                .isEmpty();
    }

    @Test
    void adjustmentNeverExceedsAbsoluteLimit() {
        Optional<ValidatedMitigationRecommendation> result = policy.evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                confirmedEvent(),
                diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 300, 300),
                impact());

        assertThat(result).hasValueSatisfying(recommendation ->
                assertThat(recommendation.adjustmentSeconds()).isEqualTo(120));
    }

    @Test
    void headwayWithoutDeficitProducesNoAction() {
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44531",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(600, 620),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-101", "arrival-observation-102"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION, event, diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 90, 120), impact()))
                .isEmpty();
    }

    @Test
    void observationOnlyEvidenceProducesNoAction() {
        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.OBSERVATION_ONLY,
                confirmedEvent(), diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 90, 120), impact()))
                .isEmpty();
    }

    private ConfirmedBunchingEvent confirmedEvent() {
        return new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44531",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(180, 210),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-101", "arrival-observation-102"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                DIAGNOSIS_ID,
                "Downstream boarding delay caused the following bus to close the gap.",
                new BigDecimal("0.82"),
                List.of("diagnosis-evidence-001"),
                List.of("Vehicle location is unavailable."));
    }

    private ValidatedMitigationContext validatedContext(
            FollowingBusState followingBusState,
            int maximumHoldSeconds,
            int maximumDwellAdjustmentSeconds) {
        return new ValidatedMitigationContext(
                "SIM-BUS-190-02",
                "44539",
                followingBusState,
                maximumHoldSeconds,
                maximumDwellAdjustmentSeconds,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED,
                List.of("validation-001"));
    }

    private SimulatedImpactEstimate impact() {
        return new SimulatedImpactEstimate(
                210,
                420,
                38,
                24,
                new BigDecimal("190"),
                new BigDecimal("128"));
    }
}
