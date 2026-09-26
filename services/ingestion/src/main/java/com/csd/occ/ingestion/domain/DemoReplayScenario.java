package com.csd.occ.ingestion.domain;

import java.time.Duration;

/** Stable route-190 demo timing pattern; headways scale with the detector threshold. */
public final class DemoReplayScenario {
    public static final int STEP_COUNT = 3;

    private DemoReplayScenario() {}

    public static Duration headway(int step, Duration threshold) {
        if (step < 0 || step >= STEP_COUNT) throw new IllegalArgumentException("demo step is out of range");
        if (threshold == null || threshold.compareTo(Duration.ofNanos(2)) < 0)
            throw new IllegalArgumentException("bunching threshold must be at least 2ns");
        return step == 0 ? threshold.multipliedBy(10).dividedBy(3) : threshold.dividedBy(2);
    }
}
