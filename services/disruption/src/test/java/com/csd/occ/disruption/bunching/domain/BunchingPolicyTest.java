package com.csd.occ.disruption.bunching.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.csd.occ.disruption.bunching.domain.BunchingEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class BunchingPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final Duration FRESHNESS = Duration.ofSeconds(120);
    private final BunchingPolicy policy = new BunchingPolicy();

    @ParameterizedTest
    @CsvSource({"0,90,BUNCHING", "45,90,BUNCHING", "89,90,BUNCHING", "90,90,NORMAL", "91,90,NORMAL", "300,90,NORMAL",
            "59,60,BUNCHING", "60,60,NORMAL", "90,120,BUNCHING", "120,120,NORMAL"})
    void appliesStrictConfigurableThreshold(long gap, long threshold, State expected) {
        var result = policy.evaluate(snapshot(NOW, arrival(1, 300), arrival(2, 300 + gap)),
                NOW, Duration.ofSeconds(threshold), FRESHNESS);
        assertEquals(expected, result.groups().getFirst().state());
        assertEquals(Duration.ofSeconds(gap), result.groups().getFirst().headways().getFirst().gap());
        assertEquals(Duration.ofSeconds(threshold), result.threshold());
    }

    @Test
    void preservesSubsecondPrecisionAtThreshold() {
        var second = new ArrivalSnapshot.Arrival(id(2), "190", "44009", Optional.of(1), 2,
                Optional.of(NOW.plusSeconds(390).minusNanos(1)));
        assertEquals(State.BUNCHING, evaluate(snapshot(NOW, arrival(1, 300), second))
                .groups().getFirst().state());
    }

    @Test
    void sortsArrivalsAndEvaluatesBothConsecutiveGaps() {
        var result = evaluate(snapshot(NOW, arrival(3, 600), arrival(1, 300), arrival(2, 360)));
        var group = result.groups().getFirst();
        assertEquals(State.BUNCHING, group.state());
        assertEquals(List.of(new Headway(id(1), id(2), Duration.ofSeconds(60), true),
                new Headway(id(2), id(3), Duration.ofSeconds(240), false)), group.headways());
    }

    @Test
    void neverPairsDifferentServicesDirectionsOrStops() {
        var arrivals = List.of(arrival(1, 300),
                new ArrivalSnapshot.Arrival(id(2), "972", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(301))),
                new ArrivalSnapshot.Arrival(id(3), "190", "44009", Optional.of(2), 1, Optional.of(NOW.plusSeconds(302))),
                new ArrivalSnapshot.Arrival(id(4), "190", "44019", Optional.of(1), 1, Optional.of(NOW.plusSeconds(303))));
        var result = evaluate(snapshot(NOW, arrivals.toArray(ArrivalSnapshot.Arrival[]::new)));
        assertEquals(4, result.groups().size());
        assertTrue(result.groups().stream().allMatch(group -> group.reason() == Reason.TOO_FEW_ARRIVALS));
    }

    @Test
    void missingDirectionStillEvaluatesTheArrivalHeadway() {
        var first = new ArrivalSnapshot.Arrival(id(1), "190", "44009", Optional.empty(), 1,
                Optional.of(NOW.plusSeconds(300)));
        var second = new ArrivalSnapshot.Arrival(id(2), "190", "44009", Optional.empty(), 2,
                Optional.of(NOW.plusSeconds(360)));
        var result = evaluate(snapshot(NOW, first, second));
        assertEquals(Reason.NONE, result.groups().getFirst().reason());
        assertEquals(State.BUNCHING, result.groups().getFirst().state());
    }

    @Test
    void missingEstimateDoesNotCreateAGapAcrossTheMissingArrival() {
        var missing = new ArrivalSnapshot.Arrival(id(2), "190", "44009", Optional.of(1), 2, Optional.empty());
        assertEquals(Reason.MISSING_ARRIVAL, evaluate(snapshot(NOW, arrival(1, 300), missing,
                arrival(3, 360))).groups().getFirst().reason());
    }

    @ParameterizedTest
    @CsvSource({"119,NONE", "120,STALE_SNAPSHOT", "121,STALE_SNAPSHOT", "-1,FUTURE_SNAPSHOT"})
    void computesFreshnessFromSuppliedServerTime(long age, Reason expected) {
        assertEquals(expected, evaluate(snapshot(NOW.minusSeconds(age), arrival(1, 300), arrival(2, 360)))
                .groups().getFirst().reason());
    }

    @Test
    void expiredArrivalEstimatesAreNotTreatedAsApproachingBuses() {
        assertEquals(Reason.PAST_ARRIVAL, evaluate(snapshot(NOW, arrival(1, -1), arrival(2, 60)))
                .groups().getFirst().reason());
    }

    @ParameterizedTest
    @CsvSource({"-1,60,120,BUNCHING,NONE", "-1,60,150,NORMAL,NONE", "-2,-1,60,INSUFFICIENT_EVIDENCE,PAST_ARRIVAL",
            "-3,-2,-1,INSUFFICIENT_EVIDENCE,PAST_ARRIVAL", "-1,0,60,BUNCHING,NONE"})
    void expiredLeadingEstimatesDoNotHideRemainingUsablePairs(long first, long second, long third, State state, Reason reason) {
        var result = evaluate(snapshot(NOW, arrival(1, first), arrival(2, second), arrival(3, third)));
        var group = result.groups().getFirst();
        assertEquals(state, group.state());
        assertEquals(reason, group.reason());
        if (reason == Reason.NONE) {
            assertEquals(1, group.headways().size());
            assertEquals(id(2), group.headways().getFirst().leadingObservationId());
            assertEquals(id(3), group.headways().getFirst().followingObservationId());
        } else assertTrue(group.headways().isEmpty());
    }

    @Test
    void duplicateRowsCannotBecomeZeroHeadwayBunching() {
        var row = arrival(1, 300);
        assertEquals(Reason.DUPLICATE_ARRIVAL, evaluate(snapshot(NOW, row, row)).groups().getFirst().reason());
    }

    @Test
    void conflictingRowsForTheSameSlotAreInsufficientEvidence() {
        var duplicateSlot = new ArrivalSnapshot.Arrival(id(2), "190", "44009", Optional.of(1), 1,
                Optional.of(NOW.plusSeconds(360)));
        assertEquals(Reason.DUPLICATE_ARRIVAL, evaluate(snapshot(NOW, arrival(1, 300), duplicateSlot))
                .groups().getFirst().reason());
    }

    @Test
    void emptySnapshotProducesNoNormalOrBunchingClaims() {
        assertTrue(evaluate(snapshot(NOW)).groups().isEmpty());
    }

    @Test
    void repeatedEvaluationPreservesEvidenceAndResult() {
        var snapshot = snapshot(NOW, arrival(1, 300), arrival(2, 360));
        var first = evaluate(snapshot);
        assertEquals(first, evaluate(snapshot));
        assertEquals(snapshot, first.snapshot());
        assertEquals(NOW, first.evaluatedAt());
        assertEquals(FRESHNESS, first.staleAfter());
    }

    @Test
    void invalidDurationsAreRejected() {
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy.evaluate(snapshot(NOW), NOW, duration, FRESHNESS));
            assertThrows(IllegalArgumentException.class,
                    () -> policy.evaluate(snapshot(NOW), NOW, BunchingPolicy.DEFAULT_THRESHOLD, duration));
        }
    }

    @Test
    void snapshotDefensivelyCopiesInputArrivals() {
        var input = new ArrayList<>(List.of(arrival(1, 300), arrival(2, 360)));
        var snapshot = new ArrivalSnapshot(id(10), id(11), id(12), NOW, input);
        input.clear();
        assertEquals(2, snapshot.arrivals().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.arrivals().clear());
        assertThrows(UnsupportedOperationException.class, () -> evaluate(snapshot).groups().clear());
    }

    private BunchingEvaluation evaluate(ArrivalSnapshot snapshot) {
        return policy.evaluate(snapshot, NOW, BunchingPolicy.DEFAULT_THRESHOLD, FRESHNESS);
    }

    // Synthetic normalized ingestion fixtures, not evidence of real DataMall behavior.
    private ArrivalSnapshot snapshot(Instant fetchedAt, ArrivalSnapshot.Arrival... arrivals) {
        return new ArrivalSnapshot(id(10), id(11), id(12), fetchedAt, List.of(arrivals));
    }

    private ArrivalSnapshot.Arrival arrival(int slot, long seconds) {
        return new ArrivalSnapshot.Arrival(id(slot), "190", "44009", Optional.of(1), slot,
                Optional.of(NOW.plusSeconds(seconds)));
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }
}
