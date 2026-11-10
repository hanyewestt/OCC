package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
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
    void legacyDiagnosisConfidenceCannotAlterHoldOrDwellRecommendations() {
        var original = diagnosis();
        for (FollowingBusState state : List.of(FollowingBusState.READY_TO_DEPART,
                FollowingBusState.BOARDING_AT_CONTROL_STOP)) {
            var context = validatedContext(state, 120, 75);
            var baseline = policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION,
                    confirmedEvent(), original, context, impact());
            assertThat(baseline).isPresent();
            for (BigDecimal legacyScore : java.util.Arrays.asList(BigDecimal.ZERO, BigDecimal.ONE, null)) {
                var legacy = new BunchingRootCauseDiagnosis(original.diagnosisId(), original.likelyCause(),
                        legacyScore, original.evidenceIds(), original.limitations());
                assertThat(legacy.confidence()).isNull();
                var actual = policy.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION,
                        confirmedEvent(), legacy, context, impact());
                assertThat(actual).isEqualTo(baseline);
                assertThat(actual.orElseThrow().explanation().diagnosisConfidence()).isNull();
            }
        }
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

    @Test
    void suggestsAnOutcomeForEachBusUsingItsOwnGapAssignmentAndProgress() {
        var now = Instant.parse("2026-09-27T23:45:02Z");
        var references = List.of("lead", "approaching", "boarding", "wide", "unassigned", "stale", "spaced", "short-deficit");
        var group = new AdvisoryContextPort.CurrentBunchingGroup(UUID.randomUUID(), EVENT_ID, "190", "44251", 1,
                UUID.randomUUID(), now.minusSeconds(10), now.plusSeconds(30), references,
                List.of(20L, 25L, 700L, 30L, 35L, 600L, 595L), List.of(true, true, true, true, true, true, true));
        var approaching = new AdvisoryContextPort.Progress("44251", 2, group.fetchedAt(), 30, List.of(), List.of(),
                now.plusSeconds(10), now.plusSeconds(30));
        var boarding = new AdvisoryContextPort.Progress("44251", 2, group.fetchedAt(), 30, List.of(), List.of(),
                now, now.plusSeconds(30), now.plusSeconds(20));
        var stale = new AdvisoryContextPort.Progress("44251", 2, group.fetchedAt(), 30, List.of(), List.of(),
                now.plusSeconds(10), now);
        var fleet = List.of(bus("approaching", approaching, UUID.randomUUID()), bus("boarding", boarding, UUID.randomUUID()),
                bus("unassigned", approaching, null), bus("stale", stale, UUID.randomUUID()),
                bus("short-deficit", approaching, UUID.randomUUID()));
        var route = new AdvisoryContextPort.Route("190", List.of(new AdvisoryContextPort.Operator("SBST", "BUS",
                List.of(new AdvisoryContextPort.Direction(1, List.of(new AdvisoryContextPort.Stop("44251", "Stop", 2)))))));

        assertThat(policy.suggestOutcomes(group, fleet, route, 600, now)).containsExactly(
                new SuggestedBusOutcome("lead", null, 0, "No preceding gap in the reviewed group."),
                new SuggestedBusOutcome("approaching", null, 0, "The adjustment would make the following gap too short."),
                new SuggestedBusOutcome("boarding", MitigationType.DWELL_ADJUSTMENT, 75, null),
                new SuggestedBusOutcome("wide", null, 0, "The preceding gap already meets the expected headway."),
                new SuggestedBusOutcome("unassigned", null, 0, "No captain is assigned to this bus."),
                new SuggestedBusOutcome("stale", null, 0, "The bus has no fresh approaching or boarding evidence."),
                new SuggestedBusOutcome("spaced", null, 0, "The preceding gap already meets the expected headway."),
                new SuggestedBusOutcome("short-deficit", MitigationType.HOLD, 5, null));
    }

    @Test
    void suppressesTimedSuggestionsTouchingAnUnreliableGap() {
        var now = Instant.parse("2026-09-27T23:45:02Z");
        var group = new AdvisoryContextPort.CurrentBunchingGroup(UUID.randomUUID(), EVENT_ID, "190", "44251", 1,
                UUID.randomUUID(), now, now.plusSeconds(30), List.of("lead", "middle", "tail"),
                List.of(20L, 30L), List.of(true, false));
        assertThat(policy.suggestOutcomes(group, List.of(), null, 600, now)).containsExactly(
                new SuggestedBusOutcome("lead", null, 0, "No preceding gap in the reviewed group."),
                new SuggestedBusOutcome("middle", null, 0, "An adjacent gap needs manual review."),
                new SuggestedBusOutcome("tail", null, 0, "An adjacent gap needs manual review."));
    }

    private static AdvisoryContextPort.Bus bus(String id, AdvisoryContextPort.Progress progress, UUID captain) {
        return new AdvisoryContextPort.Bus(id, id, id, "190", 1, captain, captain == null ? "UNASSIGNED" : "ASSIGNED", progress);
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
