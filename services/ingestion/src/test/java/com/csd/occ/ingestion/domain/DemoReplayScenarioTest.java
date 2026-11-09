package com.csd.occ.ingestion.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DemoReplayScenarioTest {
    private static final Duration THRESHOLD = Duration.ofSeconds(90);

    @Test
    void deterministicStepsIncludeA300SecondNormalGapAnd45SecondBunchedGaps() {
        assertEquals(Duration.ofSeconds(300), DemoReplayScenario.headway(0, THRESHOLD));
        assertEquals(Duration.ofSeconds(45), DemoReplayScenario.headway(1, THRESHOLD));
        assertEquals(Duration.ofSeconds(45), DemoReplayScenario.headway(2, THRESHOLD));
    }

    @Test
    void rejectsStepsOutsideTheConfiguredScenario() {
        assertThrows(IllegalArgumentException.class, () -> DemoReplayScenario.headway(-1, THRESHOLD));
        assertThrows(IllegalArgumentException.class,
                () -> DemoReplayScenario.headway(DemoReplayScenario.STEP_COUNT, THRESHOLD));
    }

    @Test
    void normalScenarioKeepsTheConfiguredDispatchGapAtEveryStep() {
        Duration gap = Duration.ofSeconds(120);
        for (int step = 0; step < DemoReplayScenario.STEP_COUNT; step++)
            assertEquals(gap, DemoReplayScenario.headway(step, gap, THRESHOLD, DemoScenario.NORMAL));
    }

    @Test
    void bunchingScenarioKeepsTheInitialGapThenClosesItBelowTheThreshold() {
        Duration gap = Duration.ofSeconds(120);
        assertEquals(gap, DemoReplayScenario.headway(0, gap, THRESHOLD, DemoScenario.BUNCHING));
        assertEquals(Duration.ofSeconds(45), DemoReplayScenario.headway(1, gap, THRESHOLD, DemoScenario.BUNCHING));
    }
}
