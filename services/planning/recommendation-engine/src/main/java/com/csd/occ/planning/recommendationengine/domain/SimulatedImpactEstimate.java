package com.csd.occ.planning.recommendationengine.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record SimulatedImpactEstimate(
        int headwayBeforeSeconds,
        int headwayAfterSeconds,
        int queueBeforePassengers,
        int queueAfterPassengers,
        BigDecimal passengerMinutesBefore,
        BigDecimal passengerMinutesAfter) {

    public SimulatedImpactEstimate {
        if (headwayBeforeSeconds < 0 || headwayAfterSeconds < 0
                || queueBeforePassengers < 0 || queueAfterPassengers < 0) {
            throw new IllegalArgumentException("headway and queue values must not be negative");
        }
        Objects.requireNonNull(passengerMinutesBefore, "passengerMinutesBefore is required");
        Objects.requireNonNull(passengerMinutesAfter, "passengerMinutesAfter is required");
        if (passengerMinutesBefore.signum() < 0 || passengerMinutesAfter.signum() < 0) {
            throw new IllegalArgumentException("passenger-minute values must not be negative");
        }
    }
}
