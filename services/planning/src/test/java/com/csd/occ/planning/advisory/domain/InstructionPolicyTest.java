package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstructionPolicyTest {
    private final InstructionPolicy policy = new InstructionPolicy();
    private final AdvisoryContextPort.Limits limits = new AdvisoryContextPort.Limits(
            10, 600, 5, Duration.ofMinutes(5), Duration.ofHours(12));
    private final AdvisoryContextPort.Bus bus = new AdvisoryContextPort.Bus("demo-190-b", "trip-190-b", "SBS5898U",
            "190", 1, UUID.randomUUID(), "ASSIGNED", new AdvisoryContextPort.Progress("44251", 10, Instant.EPOCH,
            30, List.of(5), List.of(300)));
    private final List<AdvisoryContextPort.Exposure> exposure = List.of(
            new AdvisoryContextPort.Exposure("demo-190-b", "190", 1, "44251", "Teck Whye School", 10, "PROXIMITY", "Unverified road impact"),
            new AdvisoryContextPort.Exposure("demo-190-b", "190", 1, "44261", "Community Centre", 11, "PROXIMITY", "Unverified road impact"),
            new AdvisoryContextPort.Exposure("demo-190-b", "190", 1, "44271", "Market", 12, "PROXIMITY", "Unverified road impact"));

    @Test
    void rendersConciseExactCautionAndNumericSpeedTemplates() {
        assertThat(message(InstructionType.BE_CAUTIOUS, stop(0), null))
                .isEqualTo("Disruption near Teck Whye School. Be cautious.");
        assertThat(message(InstructionType.REDUCE_SPEED, speed(5, 300), null))
                .isEqualTo("Reduce speed by 5 km/h for 5 min.");
    }

    @Test
    void rendersStopRangesIncludingZeroSubsequentStops() {
        assertThat(message(InstructionType.SLOW_DOWN_NEAR_STOPS, stop(2), null))
                .isEqualTo("Slow down near Teck Whye School and the next 2 stops.");
        assertThat(message(InstructionType.SLOW_DOWN_NEAR_STOPS, stop(0), null))
                .isEqualTo("Slow down near Teck Whye School.");
    }

    @Test
    void rejectsSpeedValuesOutsideSyntheticAllowancesOrOperationalBounds() {
        for (var parameters : List.of(speed(0, 300), speed(-5, 300), speed(11, 300), speed(10, 300),
                speed(5, 0), speed(5, 30), speed(5, 299), speed(5, 600), speed(5, 660),
                new InstructionParameters(null, null, null, null, null, 300),
                new InstructionParameters(null, null, null, null, 5, null))) {
            assertThatThrownBy(() -> message(InstructionType.REDUCE_SPEED, parameters, null))
                    .as(parameters.toString()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnknownStopOccurrencesAndSpoofedDisplayNames() {
        for (var parameters : List.of(
                new InstructionParameters("unknown", "Teck Whye School", 10, 0, null, null),
                new InstructionParameters("44251", "Teck Whye School", 99, 0, null, null),
                new InstructionParameters("44251", "Different Stop", 10, 0, null, null),
                new InstructionParameters("44251", "Teck Whye School", null, 0, null, null))) {
            assertThatThrownBy(() -> message(InstructionType.BE_CAUTIOUS, parameters, null))
                    .as(parameters.toString()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsRangesOutsideCapturedStopsAndMaximumCount() {
        for (int count : List.of(-1, 3, 6)) {
            assertThatThrownBy(() -> message(InstructionType.SLOW_DOWN_NEAR_STOPS, stop(count), null))
                    .as("subsequent count %s", count).isInstanceOf(IllegalArgumentException.class);
        }
        var otherBusExposure = List.of(new AdvisoryContextPort.Exposure("other-bus", "190", 1,
                "44251", "Teck Whye School", 10, "PROXIMITY", "Unverified road impact"));
        assertThatThrownBy(() -> policy.message(candidate(InstructionType.BE_CAUTIOUS, stop(0)), bus,
                otherBusExposure, null, limits)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesSourceRestrictionsAndRejectsTheirReplacementWithMovementAdvice() {
        assertThat(message(InstructionType.BE_CAUTIOUS, stop(0), " Avoid the affected area. "))
                .isEqualTo("Disruption near Teck Whye School. Avoid the affected area.");
        assertThatThrownBy(() -> message(InstructionType.REDUCE_SPEED, speed(5, 300), "Avoid the affected area."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(InstructionType.SLOW_DOWN_NEAR_STOPS, stop(1), "Avoid the affected area."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMissingProgressAndUnvalidatedHoldOrDwell() {
        var unavailable = new AdvisoryContextPort.Bus(bus.busId(), bus.tripId(), bus.registrationNumber(),
                bus.serviceId(), bus.directionId(), bus.captainUserId(), "ASSIGNED", null);
        assertThatThrownBy(() -> policy.message(candidate(InstructionType.BE_CAUTIOUS, stop(0)), unavailable,
                exposure, null, limits)).isInstanceOf(IllegalArgumentException.class);
        for (var type : List.of(InstructionType.HOLD, InstructionType.DWELL_ADJUSTMENT))
            assertThatThrownBy(() -> message(type, stop(0), null)).isInstanceOf(IllegalArgumentException.class);
    }

    private String message(InstructionType type, InstructionParameters parameters, String restriction) {
        return policy.message(candidate(type, parameters), bus, exposure, restriction, limits);
    }
    private AdvisoryCandidate candidate(InstructionType type, InstructionParameters parameters) {
        return new AdvisoryCandidate(type, parameters, "Controller-reviewed synthetic scenario");
    }
    private InstructionParameters stop(int count) {
        return new InstructionParameters("44251", "Teck Whye School", 10, count, null, null);
    }
    private InstructionParameters speed(int reduction, int duration) {
        return new InstructionParameters(null, null, null, null, reduction, duration);
    }
}
