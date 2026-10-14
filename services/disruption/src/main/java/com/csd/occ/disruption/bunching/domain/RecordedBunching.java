package com.csd.occ.disruption.bunching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Returns persisted metadata even when a retry supplies a different threshold or server time. */
public record RecordedBunching(UUID rawObservationId, Instant evaluatedAt,
                               BigDecimal thresholdSeconds, int bunchedGroupCount, Outcome outcome) {
    public enum Outcome { RECORDED, ALREADY_RECORDED }
}
