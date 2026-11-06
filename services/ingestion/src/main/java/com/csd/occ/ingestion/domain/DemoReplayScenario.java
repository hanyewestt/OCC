package com.csd.occ.ingestion.domain;

import java.time.Duration;

/** Stable route-190 demo timing pattern; headways scale with the detector threshold. */
public final class DemoReplayScenario {
    public static final int STEP_COUNT = 3;

    private DemoReplayScenario() {}

    public static Duration headway(int step, Duration threshold) {
        return headway(step, threshold.multipliedBy(10).dividedBy(3), threshold, DemoScenario.BUNCHING);
    }

    public static Duration headway(int step, Duration dispatchGap, Duration threshold, DemoScenario scenario) {
        if (step < 0 || step >= STEP_COUNT) throw new IllegalArgumentException("demo step is out of range");
        if (threshold == null || threshold.compareTo(Duration.ofNanos(2)) < 0)
            throw new IllegalArgumentException("bunching threshold must be at least 2ns");
        if (scenario == DemoScenario.NORMAL) return dispatchGap;
        return step == 0 ? dispatchGap : threshold.dividedBy(2);
    }
}
