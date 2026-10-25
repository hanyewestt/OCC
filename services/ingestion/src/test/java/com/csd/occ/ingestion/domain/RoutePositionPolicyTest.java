package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutePositionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");
    private static final Duration POLL = Duration.ofSeconds(60);
    private static final UUID POLL_CYCLE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void replayKeepsThreeBusesFromOneStopSnapshotEvenWhenTheirCoordinatesAreIdentical() {
        UUID snapshotId = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(snapshotId, "05039", 7, 1, 1_200, 1_400, 132),
                reading(snapshotId, "05039", 7, 2, 1_200, 1_400, 226),
                reading(snapshotId, "05039", 7, 3, 1_200, 1_400, 603)));

        assertThat(buses).hasSize(3);
        assertThat(buses).extracting(RoutePositionBus::order).containsExactly(1, 2, 3);
        assertThat(buses).extracting(RoutePositionBus::distanceAlongRouteM).containsOnly(1_200.0);
        assertThat(buses.get(0).evidenceObservationIds()).hasSize(1);
        assertThat(buses.get(1).evidenceObservationIds()).hasSize(1);
        assertThat(buses.get(2).evidenceObservationIds()).hasSize(1);
    }

    @Test
    void assignmentUsesPositionAndEtaAndKeepsSnapshotOrder() {
        UUID upstreamSnapshot = UUID.randomUUID();
        UUID downstreamSnapshot = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(upstreamSnapshot, "05039", 7, 1, 1_000, 1_000, 600, 60),
                reading(upstreamSnapshot, "05039", 7, 2, 700, 1_000, 720, 60),
                reading(downstreamSnapshot, "05019", 8, 1, 1_400, 1_500, 660, 5),
                reading(downstreamSnapshot, "05019", 8, 2, 1_100, 1_500, 780, 5)));

        assertThat(buses).hasSize(2);
        assertThat(buses.get(0).evidenceObservationIds()).containsExactlyInAnyOrder(
                observation(upstreamSnapshot, "05039", 1), observation(downstreamSnapshot, "05019", 1));
        assertThat(buses.get(1).evidenceObservationIds()).containsExactlyInAnyOrder(
                observation(upstreamSnapshot, "05039", 2), observation(downstreamSnapshot, "05019", 2));
    }

    @Test
    void inconsistentEtaAddsCostButDoesNotDiscardPositionMatch() {
        UUID upstreamSnapshot = UUID.randomUUID();
        UUID downstreamSnapshot = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(upstreamSnapshot, "05039", 7, 1, 1_000, 1_000, 600, 60),
                reading(upstreamSnapshot, "05039", 7, 2, 700, 1_000, 720, 60),
                reading(downstreamSnapshot, "05019", 8, 1, 1_000, 1_500, 2_000, 5),
                reading(downstreamSnapshot, "05019", 8, 2, 700, 1_500, 2_120, 5)));

        assertThat(buses).hasSize(2);
        assertThat(buses.get(0).evidenceObservationIds()).containsExactlyInAnyOrder(
                observation(upstreamSnapshot, "05039", 1), observation(downstreamSnapshot, "05019", 1));
        assertThat(buses.get(1).evidenceObservationIds()).containsExactlyInAnyOrder(
                observation(upstreamSnapshot, "05039", 2), observation(downstreamSnapshot, "05019", 2));
    }

    @Test
    void assignmentMatchesDifferentStopsInTheSameBatchButKeepsEachStopSnapshotDistinct() {
        UUID batchId = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(batchId, "05039", 7, 1, 1_200, 1_400, 132, 5),
                reading(batchId, "05039", 7, 2, 1_200, 1_400, 226, 5),
                reading(batchId, "05039", 7, 3, 1_200, 1_400, 603, 5),
                reading(batchId, "05019", 8, 1, 1_200, 1_500, 210, 5),
                reading(batchId, "05019", 8, 2, 1_200, 1_500, 304, 5),
                reading(batchId, "05019", 8, 3, 1_200, 1_500, 681, 5)));

        assertThat(buses).hasSize(3);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(2));
    }

    @Test
    void assignmentProcessesNearSimultaneousStopPollsInRouteOrderEvenWhenFetchTimesArriveOutOfOrder() {
        UUID batchId = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(batchId, "05019", 27, 1, 1_000, 2_000, 240, 13),
                reading(batchId, "05019", 27, 2, 900, 2_000, 340, 13),
                reading(batchId, "05019", 27, 3, 800, 2_000, 440, 13),
                reading(batchId, "05039", 25, 1, 1_000, 1_000, 120, 11),
                reading(batchId, "05039", 25, 2, 900, 1_000, 220, 11),
                reading(batchId, "05039", 25, 3, 800, 1_000, 320, 11),
                reading(batchId, "05069", 26, 1, 1_000, 1_500, 180, 12),
                reading(batchId, "05069", 26, 2, 900, 1_500, 280, 12),
                reading(batchId, "05069", 26, 3, 800, 1_500, 380, 12)));

        assertThat(buses).hasSize(3);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(3));
    }

    @Test
    void sameCycleMatchingDoesNotCreateTracksWhenFeedCoordinatesJumpBetweenStops() {
        UUID batchId = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(batchId, "45009", 1, 1, 1_147, 0, 700, 5),
                reading(batchId, "45009", 1, 2, 1_147, 0, 1_500, 5),
                reading(batchId, "45009", 1, 3, 1_147, 0, 2_300, 5),
                reading(batchId, "44641", 2, 1, 37, 1_000, 50, 4),
                reading(batchId, "44641", 2, 2, 1_147, 1_000, 780, 4),
                reading(batchId, "44641", 2, 3, 1_147, 1_000, 1_600, 4)));

        assertThat(buses).hasSize(3);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(2));
    }

    @Test
    void sameCycleCoordinateOutlierIsClampedInsteadOfBecomingAStrayBus() {
        UUID batchId = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(batchId, "44339", 15, 1, 3_138, 5_000, 160, 5),
                reading(batchId, "44339", 15, 2, 1_147, 5_000, 1_160, 5),
                reading(batchId, "44339", 15, 3, 1_147, 5_000, 1_780, 5),
                reading(batchId, "09219", 25, 1, 25_057, 6_000, 640, 4),
                reading(batchId, "09219", 25, 2, 3_138, 6_000, 1_440, 4),
                reading(batchId, "09219", 25, 3, 1_147, 6_000, 2_060, 4)));

        assertThat(buses).hasSize(3);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(2));
        assertThat(buses).extracting(RoutePositionBus::distanceAlongRouteM).doesNotContain(10_000.0);
    }

    @Test
    void suppressesSingleObservationStrayWhenThreeBusesHaveIndependentStopSupport() {
        UUID firstStop = UUID.randomUUID();
        UUID secondStop = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(firstStop, "05039", 7, 1, 1_000, 1_000, 300, 55),
                reading(firstStop, "05039", 7, 2, 900, 1_000, 420, 55),
                reading(firstStop, "05039", 7, 3, 800, 1_000, 540, 55),
                reading(secondStop, "05019", 8, 1, 1_400, 1_500, 360, 5),
                reading(secondStop, "05019", 8, 2, 1_300, 1_500, 480, 5),
                reading(secondStop, "05019", 8, 3, 1_200, 1_500, 600, 5),
                reading(secondStop, "05019", 8, 4, 9_900, 1_500, 720, 5)));

        assertThat(buses).hasSize(3);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.supportingStopCount()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void latestPollingPassCeilingSuppressesAnOlderUnmatchedTrack() {
        UUID oldCycle = UUID.randomUUID();
        UUID latestCycle = UUID.randomUUID();
        List<RoutePositionReading> readings = List.of(
                reading(oldCycle, "05039", 7, 1, 1_000, 1_000, 300, 65, oldCycle),
                reading(UUID.randomUUID(), "05039", 7, 1, 3_000, 1_000, 420, 65, oldCycle),
                reading(UUID.randomUUID(), "05039", 7, 1, 5_000, 1_000, 540, 65, oldCycle),
                reading(UUID.randomUUID(), "05039", 7, 1, 7_000, 1_000, 660, 65, oldCycle),
                reading(latestCycle, "05039", 7, 1, 1_100, 1_000, 300, 5),
                reading(latestCycle, "05039", 7, 2, 3_100, 1_000, 420, 5),
                reading(latestCycle, "05039", 7, 3, 5_100, 1_000, 540, 5));

        assertThat(estimate(readings)).hasSize(3);
    }

    @Test
    void dropsStaleReadingsAndClampsProjectedPositionsAtTheEndOfTheRoute() {
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(stale, "05039", 7, 1, 500, 1_000, 10, 121),
                reading(fresh, "05039", 7, 1, 12_000, 1_000, 10)));

        assertThat(buses).singleElement().extracting(RoutePositionBus::distanceAlongRouteM).isEqualTo(10_000.0);
    }

    @Test
    void keepsOnePollingPassTogetherEvenWhenStopRequestsTakeLongerThanTenSeconds() {
        UUID firstStopSnapshot = UUID.randomUUID();
        UUID secondStopSnapshot = UUID.randomUUID();
        List<RoutePositionBus> buses = estimate(List.of(
                reading(firstStopSnapshot, "05039", 7, 1, 1_000, 1_000, 300, 55),
                reading(firstStopSnapshot, "05039", 7, 2, 700, 1_000, 420, 55),
                reading(secondStopSnapshot, "05019", 8, 1, 1_000, 1_500, 360, 5),
                reading(secondStopSnapshot, "05019", 8, 2, 700, 1_500, 480, 5)));

        assertThat(buses).hasSize(2);
        assertThat(buses).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(2));
    }

    @Test
    void excludesPreCycleReadingsInsteadOfGuessingTheirPollingPass() {
        UUID snapshot = UUID.randomUUID();
        RoutePositionReading legacyReading = new RoutePositionReading(observation(snapshot, "05039", 1), snapshot,
                null, "05039", 7, 1, 1_000, 1_000, NOW.plusSeconds(300), NOW.minusSeconds(5));

        assertThat(estimate(List.of(legacyReading))).isEmpty();
    }

    @Test
    void showsFourBusesWhenFourthTrackIsDiscoveredAcrossPollCycles() {
        UUID cycle1 = UUID.randomUUID();
        UUID cycle2 = UUID.randomUUID();
        UUID snap1 = UUID.randomUUID();
        UUID snap2 = UUID.randomUUID();
        UUID snap3 = UUID.randomUUID();
        UUID snap4 = UUID.randomUUID();
        UUID snap5 = UUID.randomUUID();
        List<RoutePositionReading> readings = List.of(
                // Cycle 1, stop 05039 (seq 7): 3 buses (A, B, C)
                reading(snap1, "05039", 7, 1, 7_000, 1_000, 120, 65, cycle1),
                reading(snap1, "05039", 7, 2, 4_000, 1_000, 360, 65, cycle1),
                reading(snap1, "05039", 7, 3, 1_000, 1_000, 600, 65, cycle1),
                // Cycle 1, stop 05019 (seq 8): same 3 buses, shifted slightly
                reading(snap2, "05019", 8, 1, 7_200, 1_500, 105, 64, cycle1),
                reading(snap2, "05019", 8, 2, 4_200, 1_500, 345, 64, cycle1),
                reading(snap2, "05019", 8, 3, 1_200, 1_500, 585, 64, cycle1),
                // Cycle 2, stop 05059 (seq 6): new bus D observed upstream
                reading(snap5, "05059", 6, 1, 200, 500, 960, 5, cycle2),
                // Cycle 2, stop 05039 (seq 7): buses B, C moved forward + new bus D at 200 m
                reading(snap3, "05039", 7, 1, 4_500, 1_000, 300, 5, cycle2),
                reading(snap3, "05039", 7, 2, 1_500, 1_000, 540, 5, cycle2),
                reading(snap3, "05039", 7, 3, 200, 1_000, 900, 5, cycle2),
                // Cycle 2, stop 05019 (seq 8): bus A still visible here + B, C
                reading(snap4, "05019", 8, 1, 7_600, 1_500, 60, 4, cycle2),
                reading(snap4, "05019", 8, 2, 4_700, 1_500, 285, 4, cycle2),
                reading(snap4, "05019", 8, 3, 1_700, 1_500, 525, 4, cycle2));

        List<RoutePositionBus> buses = RoutePositionPolicy.estimate(readings, 10_000, NOW, POLL, 2, 150, 16.7);

        assertThat(buses).hasSize(4);
        assertThat(buses.stream().filter(bus -> bus.supportingStopCount() >= 2).count())
                .as("well-supported buses seen at multiple stops").isGreaterThanOrEqualTo(4);
    }

    @Test
    void suppressedBusCountReflectsOnlySingleStopStraysBeyondTheCeiling() {
        UUID cycle1 = UUID.randomUUID();
        UUID cycle2 = UUID.randomUUID();
        UUID snap1 = UUID.randomUUID();
        UUID snap2 = UUID.randomUUID();
        UUID snap3 = UUID.randomUUID();
        List<RoutePositionReading> readings = List.of(
                // Cycle 1, stop 05039: 3 buses
                reading(snap1, "05039", 7, 1, 7_000, 1_000, 120, 65, cycle1),
                reading(snap1, "05039", 7, 2, 4_000, 1_000, 360, 65, cycle1),
                reading(snap1, "05039", 7, 3, 1_000, 1_000, 600, 65, cycle1),
                // Cycle 2, stop 05039: B, C still visible + new D
                reading(snap2, "05039", 7, 1, 4_500, 1_000, 300, 5, cycle2),
                reading(snap2, "05039", 7, 2, 1_500, 1_000, 540, 5, cycle2),
                reading(snap2, "05039", 7, 3, 200, 1_000, 900, 5, cycle2),
                // Cycle 2, stop 05019: A still visible + B, C
                reading(snap3, "05019", 8, 1, 7_600, 1_500, 60, 4, cycle2),
                reading(snap3, "05019", 8, 2, 4_700, 1_500, 285, 4, cycle2),
                reading(snap3, "05019", 8, 3, 1_700, 1_500, 525, 4, cycle2));

        RoutePositionPolicy.RoutePositionEstimate estimate = RoutePositionPolicy.estimateWithConfidence(
                readings, 10_000, NOW, POLL, 2, 150, 16.7);

        assertThat(estimate.buses()).hasSizeGreaterThanOrEqualTo(3);
        // The suppressed count should not include well-supported tracks
        assertThat(estimate.suppressedBusCount()).isLessThanOrEqualTo(1);
    }

    @Test
    void retainsEvidenceObservationsAcrossManySupportingStopsWithoutPrematureTruncation() {
        UUID batchId = UUID.randomUUID();
        java.util.List<RoutePositionReading> readings = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String stopCode = String.format("STOP%02d", i);
            readings.add(reading(batchId, stopCode, i, 1, 5_000, 500.0 * i, 120 + 30 * i));
        }

        List<RoutePositionBus> buses = estimate(readings);
        assertThat(buses).singleElement();
        assertThat(buses.get(0).supportingStopCount()).isEqualTo(20);
        assertThat(buses.get(0).evidenceObservationIds()).hasSize(20);
    }

    @Test
    void preventsDistinctBusesWithShiftedVisitOrdersFromMergingInSameCycle() {
        UUID cycle = UUID.randomUUID();
        UUID snap1 = UUID.randomUUID();
        UUID snap2 = UUID.randomUUID();
        List<RoutePositionReading> readings = List.of(
                reading(snap1, "40239", 14, 1, 6_663, 12_000, 360, 5, cycle),
                reading(snap1, "40239", 14, 2, 1_782, 12_000, 960, 5, cycle),
                reading(snap2, "40219", 16, 1, 12_487, 12_700, 30, 4, cycle),
                reading(snap2, "40219", 16, 2, 6_663, 12_700, 420, 4, cycle),
                reading(snap2, "40219", 16, 3, 1_782, 12_700, 1020, 4, cycle));

        List<RoutePositionBus> buses = RoutePositionPolicy.estimate(readings, 20_000, NOW, POLL, 2, 150, 16.7);

        assertThat(buses).extracting(RoutePositionBus::distanceAlongRouteM)
                .contains(6_663.0, 1_782.0);
        assertThat(buses.stream().filter(b -> Math.abs(b.distanceAlongRouteM() - 6_663.0) < 1.0).findFirst().orElseThrow()
                .supportingStopCount()).isEqualTo(2);
        assertThat(buses.stream().filter(b -> Math.abs(b.distanceAlongRouteM() - 1_782.0) < 1.0).findFirst().orElseThrow()
                .supportingStopCount()).isEqualTo(2);
    }

    private List<RoutePositionBus> estimate(List<RoutePositionReading> readings) {
        return RoutePositionPolicy.estimate(readings, 10_000, NOW, POLL, 2, 150, 16.7);
    }

    private RoutePositionReading reading(UUID snapshot, String stop, int sequence, int order, double position,
            double stopPosition, long etaSeconds) {
        return reading(snapshot, stop, sequence, order, position, stopPosition, etaSeconds, 5);
    }

    private RoutePositionReading reading(UUID snapshot, String stop, int sequence, int order, double position,
            double stopPosition, long etaSeconds, long fetchedSecondsAgo) {
        return reading(snapshot, stop, sequence, order, position, stopPosition, etaSeconds, fetchedSecondsAgo, POLL_CYCLE);
    }

    private RoutePositionReading reading(UUID snapshot, String stop, int sequence, int order, double position,
            double stopPosition, long etaSeconds, long fetchedSecondsAgo, UUID pollCycle) {
        return new RoutePositionReading(observation(snapshot, stop, order), snapshot, pollCycle, stop, sequence, order, position,
                stopPosition, NOW.plusSeconds(etaSeconds), NOW.minusSeconds(fetchedSecondsAgo));
    }

    private UUID observation(UUID snapshot, String stop, int order) {
        return UUID.nameUUIDFromBytes((snapshot + ":" + stop + ":" + order).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
