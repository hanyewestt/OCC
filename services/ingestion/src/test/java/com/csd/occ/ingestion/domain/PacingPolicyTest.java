package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PacingPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");
    private static final Duration TARGET = Duration.ofSeconds(180);
    private static final Duration STALE_AFTER = Duration.ofSeconds(180);
    private final PacingPolicy policy = new PacingPolicy();

    @Test
    void middleBusHasAGapAheadAndBehindWhileTheEndsLackOneNeighbour() {
        var metrics = evaluate(List.of(
                live("44251", 190, NOW.plusSeconds(60)),
                live("44251", 190, NOW.plusSeconds(105)),
                live("44251", 190, NOW.plusSeconds(400))));

        assertThat(metrics).extracting(PacingMetric::vehicleReference)
                .containsExactly("LIVE-190-D1-44251-1", "LIVE-190-D1-44251-2", "LIVE-190-D1-44251-3");
        assertThat(metrics.get(0).ahead()).isEmpty();
        assertThat(metrics.get(0).behind()).contains(new PacingGap(Duration.ofSeconds(45), PacingState.TOO_CLOSE));
        assertThat(metrics.get(1).ahead()).contains(new PacingGap(Duration.ofSeconds(45), PacingState.TOO_CLOSE));
        assertThat(metrics.get(1).behind()).contains(new PacingGap(Duration.ofSeconds(295), PacingState.TOO_FAR));
        assertThat(metrics.get(2).ahead()).contains(new PacingGap(Duration.ofSeconds(295), PacingState.TOO_FAR));
        assertThat(metrics.get(2).behind()).isEmpty();
    }

    @Test
    void stateBoundariesFollowFiftyAndOneHundredFiftyPercentOfTarget() {
        assertThat(classify(90_000)).isEqualTo(PacingState.TOO_CLOSE);
        assertThat(classify(90_001)).isEqualTo(PacingState.ON_TARGET);
        assertThat(classify(269_999)).isEqualTo(PacingState.ON_TARGET);
        assertThat(classify(270_000)).isEqualTo(PacingState.TOO_FAR);
    }

    @Test
    void predictionsAreOrderedByEstimatedArrivalNotInputOrder() {
        var metrics = evaluate(List.of(
                simulation("DEMO-BUS-190-02", NOW.plusSeconds(165)),
                simulation("DEMO-BUS-190-01", NOW.plusSeconds(120))));

        assertThat(metrics).extracting(PacingMetric::vehicleReference)
                .containsExactly("DEMO-BUS-190-01", "DEMO-BUS-190-02");
        assertThat(metrics.get(1).ahead().orElseThrow().headway()).isEqualTo(Duration.ofSeconds(45));
        assertThat(metrics.get(1).classification()).isEqualTo("SIMULATION");
    }

    @Test
    void stopsServicesDirectionsAndClassificationsArePacedSeparately() {
        var metrics = evaluate(List.of(
                live("44251", 190, NOW.plusSeconds(60)),
                live("44259", 190, NOW.plusSeconds(70)),
                new PacingArrival("OBSERVATION_ONLY", "44251", "190", 2, NOW.plusSeconds(80), NOW, Optional.empty()),
                simulation("DEMO-BUS-190-01", NOW.plusSeconds(90))));

        assertThat(metrics).hasSize(4).allSatisfy(metric -> {
            assertThat(metric.ahead()).isEmpty();
            assertThat(metric.behind()).isEmpty();
        });
    }

    @Test
    void staleAndFuturePredictionSetsAreNotPaced() {
        var stale = new PacingArrival("OBSERVATION_ONLY", "44251", "190", 1, NOW.plusSeconds(60),
                NOW.minus(STALE_AFTER), Optional.empty());
        var future = new PacingArrival("OBSERVATION_ONLY", "44259", "190", 1, NOW.plusSeconds(60),
                NOW.plusSeconds(1), Optional.empty());
        var fresh = new PacingArrival("OBSERVATION_ONLY", "44260", "190", 1, NOW.plusSeconds(60),
                NOW.minus(STALE_AFTER).plusMillis(1), Optional.empty());

        assertThat(evaluate(List.of(stale, future, fresh))).extracting(PacingMetric::stopCode).containsExactly("44260");
    }

    @Test
    void invalidThresholdsAreRejected() {
        assertThatThrownBy(() -> policy.evaluate(List.of(), NOW, Duration.ZERO, 0.5, 1.5, STALE_AFTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.evaluate(List.of(), NOW, TARGET, 1.0, 1.5, STALE_AFTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.evaluate(List.of(), NOW, TARGET, 0.5, 1.0, STALE_AFTER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PacingState classify(long headwayMillis) {
        return policy.classify(Duration.ofMillis(headwayMillis), TARGET, 0.5, 1.5);
    }

    private List<PacingMetric> evaluate(List<PacingArrival> arrivals) {
        return policy.evaluate(arrivals, NOW, TARGET, 0.5, 1.5, STALE_AFTER);
    }

    private static PacingArrival live(String stopCode, int service, Instant eta) {
        return new PacingArrival("OBSERVATION_ONLY", stopCode, Integer.toString(service), 1, eta, NOW, Optional.empty());
    }

    private static PacingArrival simulation(String registration, Instant eta) {
        return new PacingArrival("SIMULATION", "44251", "190", 1, eta, NOW, Optional.of(registration));
    }
}
