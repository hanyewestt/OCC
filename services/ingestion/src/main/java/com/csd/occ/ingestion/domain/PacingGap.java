package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.util.Objects;

/** Predicted time separation between one bus and its neighbour on the same service and direction. */
public record PacingGap(Duration headway, PacingState state) {
    public PacingGap {
        Objects.requireNonNull(headway, "headway");
        Objects.requireNonNull(state, "state");
    }
}
