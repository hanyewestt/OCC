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

    @Test
    void clustersRepeatedReadingsAndEstimatesSpeedFromThePriorCycle() {
        List<RoutePositionBus> buses = estimate(List.of(
                reading(600, 70), reading(1_000, 5), reading(1_090, 6)));

        assertThat(buses).containsExactly(new RoutePositionBus(1, 1_045, 50, 445.0 / 65));
    }

    @Test
    void keepsSourceObservationIdsOnThePositionEstimate() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<RoutePositionBus> buses = RoutePositionPolicy.estimate(List.of(
                new RoutePositionReading(first, 1_000, NOW.minusSeconds(5)),
                new RoutePositionReading(second, 1_090, NOW.minusSeconds(6))),
                10_000, NOW, POLL, 2, 150, 16.7);

        assertThat(buses).singleElement().satisfies(bus ->
                assertThat(bus.evidenceObservationIds()).containsExactlyInAnyOrder(first, second));
    }

    @Test
    void ordersBusesFromTheFrontWithoutPreservingAnIdentityWhenTheyOvertake() {
        List<RoutePositionBus> buses = estimate(List.of(
                reading(1_800, 70), reading(2_400, 70), reading(2_000, 5), reading(2_500, 6)));

        assertThat(buses).extracting(RoutePositionBus::order).containsExactly(1, 2);
        assertThat(buses).extracting(RoutePositionBus::distanceAlongRouteM).containsExactly(2_500.0, 2_000.0);
    }

    @Test
    void dropsStaleReadingsAndClampsPositionsAtTheEndOfTheRoute() {
        List<RoutePositionBus> buses = estimate(List.of(reading(500, 121), reading(12_000, 5)));

        assertThat(buses).containsExactly(new RoutePositionBus(1, 10_000, 50, null));
    }

    @Test
    void returnsOneBusWithUnknownSpeedAndReturnsNoBusesForEmptyOrStaleInput() {
        assertThat(estimate(List.of(reading(1_000, 5))).getFirst().speedMps()).isNull();
        assertThat(estimate(List.of())).isEmpty();
        assertThat(estimate(List.of(reading(1_000, 121)))).isEmpty();
    }

    private List<RoutePositionBus> estimate(List<RoutePositionReading> readings) {
        return RoutePositionPolicy.estimate(readings, 10_000, NOW, POLL, 2, 150, 16.7);
    }

    private RoutePositionReading reading(double distance, long secondsAgo) {
        return new RoutePositionReading(distance, NOW.minusSeconds(secondsAgo));
    }
}
