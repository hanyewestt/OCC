package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaptainRoutePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(180);
    // Sequences 7..10 with distances along the route in kilometres; the last stop has no distance.
    private static final List<ReferenceTopologyPort.BusRouteStop> ROUTE = List.of(
            stop(7, "44141", "4.0"), stop(8, "44251", "4.5"), stop(9, "44241", "5.5"), stop(10, "44231", null));
    private final CaptainRoutePolicy policy = new CaptainRoutePolicy();

    @Test
    void approachingBusKeepsThePredictionForTheNextStopAndEstimatesLaterStopsFromDistance() {
        var plan = policy.plan(ROUTE, progress(8, NOW.plusSeconds(60), 30), NOW, STALE_AFTER);

        assertThat(plan.position().source()).isEqualTo(CaptainRoute.Position.Source.SIMULATION);
        assertThat(plan.position().lastStopSequence()).contains(7);
        assertThat(plan.position().nextStopSequence()).contains(8);
        assertThat(plan.position().observedAt()).contains(NOW.minusSeconds(5));
        assertThat(plan.stops().get(0).arrivalAt()).isEmpty();
        assertThat(plan.stops().get(1).arrivalAt()).contains(NOW.plusSeconds(60));
        assertThat(plan.stops().get(1).arrivalKind()).contains(CaptainRoute.ArrivalKind.PREDICTED);
        // 1.0 km at 30 km/h is 120 seconds after the predicted arrival at the next stop.
        assertThat(plan.stops().get(2).arrivalAt()).contains(NOW.plusSeconds(180));
        assertThat(plan.stops().get(2).arrivalKind()).contains(CaptainRoute.ArrivalKind.ESTIMATED);
        // Without a route distance no time is guessed.
        assertThat(plan.stops().get(3).arrivalAt()).isEmpty();
        assertThat(plan.stops().get(3).arrivalKind()).isEmpty();
    }

    @Test
    void afterThePredictedArrivalTheBusHasLeftThatStop() {
        var plan = policy.plan(ROUTE, progress(8, NOW.minusSeconds(10), 30), NOW, STALE_AFTER);

        assertThat(plan.position().lastStopSequence()).contains(8);
        assertThat(plan.position().nextStopSequence()).contains(9);
        assertThat(plan.stops().get(1).arrivalAt()).isEmpty();
        assertThat(plan.stops().get(2).arrivalAt()).contains(NOW.plusSeconds(110));
    }

    @Test
    void theFirstAndFinalStopsHaveNoStopBeforeOrAfterThem() {
        assertThat(policy.plan(ROUTE, progress(7, NOW.plusSeconds(30), 30), NOW, STALE_AFTER).position()
                .lastStopSequence()).isEmpty();
        assertThat(policy.plan(ROUTE, progress(10, NOW.minusSeconds(30), 30), NOW, STALE_AFTER).position()
                .nextStopSequence()).isEmpty();
    }

    @Test
    void staleFutureOffRouteOrMissingProgressGivesTheRouteWithoutAPosition() {
        var stale = new CaptainRoutePolicy.Progress(8, NOW.plusSeconds(60), NOW.minus(STALE_AFTER), 30);
        var future = new CaptainRoutePolicy.Progress(8, NOW.plusSeconds(60), NOW.plusSeconds(1), 30);
        for (var progress : List.of(Optional.of(stale), Optional.of(future), progress(99, NOW.plusSeconds(60), 30),
                progress(8, NOW.plusSeconds(60), 0), Optional.<CaptainRoutePolicy.Progress>empty())) {
            var plan = policy.plan(ROUTE, progress, NOW, STALE_AFTER);
            assertThat(plan.position()).isEqualTo(CaptainRoute.Position.unavailable());
            assertThat(plan.stops()).hasSize(4).allSatisfy(stop -> assertThat(stop.arrivalAt()).isEmpty());
        }
    }

    @Test
    void freshnessLimitMustBePositive() {
        assertThatThrownBy(() -> policy.plan(ROUTE, Optional.empty(), NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Optional<CaptainRoutePolicy.Progress> progress(int sequence, Instant arrival, int speedKmh) {
        return Optional.of(new CaptainRoutePolicy.Progress(sequence, arrival, NOW.minusSeconds(5), speedKmh));
    }

    private static ReferenceTopologyPort.BusRouteStop stop(int sequence, String code, String distanceKm) {
        return new ReferenceTopologyPort.BusRouteStop(code, "Road", "Stop " + code, BigDecimal.ONE, BigDecimal.ONE,
                sequence, distanceKm == null ? null : new BigDecimal(distanceKm));
    }
}
