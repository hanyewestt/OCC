package com.csd.occ.planning.recommendation.domain;

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
                validatedContext(FollowingBusState.READY_TO_DEPART, 120, 75),
                impact());

        assertThat(result).hasValueSatisfying(recommendation -> {
            assertThat(recommendation.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(recommendation.adjustmentSeconds()).isEqualTo(120);
            assertThat(recommendation.targetBusReference()).isEqualTo("SBS5898U");
            assertThat(recommendation.controlStopCode()).isEqualTo("44241");
            assertThat(recommendation.evidenceIds())
                    .containsExactly("route190-arrival-20260928T074402+0800",
                            "route190-arrival-20260928T074502+0800",
                            "route190-breakdown-delay-sbs8027y",
                            "route190-reference-directive-first-hold");
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
                "SBS5898U",
                "44241",
                FollowingBusState.READY_TO_DEPART,
                90,
                120,
                CrowdSafetyStatus.FAILED,
                MitigationValidationStatus.VALIDATED,
                List.of("route190-reference-directive-first-hold"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION,
                confirmedEvent(), diagnosis(), context, impact()))
                .isEmpty();
    }

    @Test
    void rejectedOperationalValidationProducesNoAction() {
        ValidatedMitigationContext context = new ValidatedMitigationContext(
                "SBS5898U",
                "44241",
                FollowingBusState.READY_TO_DEPART,
                90,
                120,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.REJECTED,
                List.of("route190-reference-directive-first-hold"));

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
                "44251",
                BunchingEventStatus.INSUFFICIENT_EVIDENCE,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("route190-arrival-20260928T074402+0800",
                        "route190-arrival-20260928T074502+0800"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION, event, diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 120, 75), impact()))
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
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(600, 620),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("route190-arrival-20260928T074402+0800",
                        "route190-arrival-20260928T074502+0800"));

        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION, event, diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 120, 75), impact()))
                .isEmpty();
    }

    @Test
    void observationOnlyEvidenceProducesNoAction() {
        assertThat(policy.evaluate(RECOMMENDATION_ID, SourceClassification.OBSERVATION_ONLY,
                confirmedEvent(), diagnosis(),
                validatedContext(FollowingBusState.READY_TO_DEPART, 120, 75), impact()))
                .isEmpty();
    }

    private ConfirmedBunchingEvent confirmedEvent() {
        return new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("route190-arrival-20260928T074402+0800",
                        "route190-arrival-20260928T074502+0800"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                DIAGNOSIS_ID,
                "A vehicle breakdown delayed leader SBS8027Y, allowing follower SBS5898U to close the gap.",
                new BigDecimal("0.82"),
                List.of("route190-breakdown-delay-sbs8027y"),
                List.of("The diagnosis is supplied by the shared deterministic simulation fixture."));
    }

    private ValidatedMitigationContext validatedContext(
            FollowingBusState followingBusState,
            int maximumHoldSeconds,
            int maximumDwellAdjustmentSeconds) {
        return new ValidatedMitigationContext(
                "SBS5898U",
                "44241",
                followingBusState,
                maximumHoldSeconds,
                maximumDwellAdjustmentSeconds,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED,
                List.of("route190-reference-directive-first-hold"));
    }

    private SimulatedImpactEstimate impact() {
        return new SimulatedImpactEstimate(
                221,
                341,
                38,
                24,
                new BigDecimal("190"),
                new BigDecimal("128"));
    }
}
