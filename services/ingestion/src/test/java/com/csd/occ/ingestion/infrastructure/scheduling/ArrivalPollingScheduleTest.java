package com.csd.occ.ingestion.infrastructure.scheduling;

import com.csd.occ.ingestion.domain.*;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import com.csd.occ.ingestion.infrastructure.config.ArrivalSchedulingProperties;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArrivalPollingScheduleTest {
    @Test
    void sevenSlowTargetsDoNotHoldUpTheEighthAndDispatchRateIsBounded() throws Exception {
        var telemetry = mock(ArrivalTelemetryPort.class);
        var service = mock(ArrivalPollingService.class);
        var targets = targets(8);
        when(telemetry.targets()).thenReturn(targets);
        var release = new CountDownLatch(1);
        var started = new Semaphore(0);
        var fast = new CountDownLatch(1);
        when(service.poll(any(), any(), anyInt())).thenAnswer(invocation -> {
            ArrivalPollTarget target = invocation.getArgument(0);
            started.release();
            if (target.equals(targets.getLast())) fast.countDown();
            else assertTrue(release.await(5, TimeUnit.SECONDS));
            return Optional.empty();
        });
        var clock = new MutableClock();
        var schedule = new ArrivalPollingSchedule(telemetry, service, properties(), new ArrivalSchedulingProperties(8, 1), clock);
        try {
            for (int second = 0; second < 8; second++) {
                clock.now = Instant.EPOCH.plusSeconds(second);
                schedule.poll();
                assertTrue(started.tryAcquire(2, TimeUnit.SECONDS));
                schedule.poll(); // Repeated ticks in the same second must not burst.
                assertEquals(0, started.availablePermits());
            }
            assertTrue(fast.await(2, TimeUnit.SECONDS));
            assertEquals(1, release.getCount()); // All seven slow calls are still blocked.
            verify(service, times(8)).poll(any(), any(), anyInt());
        } finally { release.countDown(); schedule.close(); }
    }

    @Test
    void saturatedPoolDoesNotQueueRequestsOrRepeatActiveTargets() throws Exception {
        var telemetry = mock(ArrivalTelemetryPort.class);
        var service = mock(ArrivalPollingService.class);
        when(telemetry.targets()).thenReturn(targets(3));
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        when(service.poll(any(), any(), anyInt())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });
        var clock = new MutableClock();
        var schedule = new ArrivalPollingSchedule(telemetry, service, properties(), new ArrivalSchedulingProperties(2, 2), clock);
        try {
            schedule.poll();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            clock.now = Instant.EPOCH.plusSeconds(61);
            schedule.poll();
            verify(service, times(2)).poll(any(), any(), anyInt());
        } finally { release.countDown(); schedule.close(); }
    }

    @Test
    void schedulingLimitsRejectUnboundedOrInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new ArrivalSchedulingProperties(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalSchedulingProperties(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalSchedulingProperties(2, 3));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalSchedulingProperties(65, 1));
    }

    private List<ArrivalPollTarget> targets(int count) {
        return IntStream.range(0, count).mapToObj(index -> new ArrivalPollTarget("DATAMALL", "stop:" + (44000 + index), "" + (44000 + index))).toList();
    }

    private ArrivalPollingProperties properties() {
        return new ArrivalPollingProperties(URI.create("http://localhost"), "synthetic", Duration.ofSeconds(10), Duration.ofSeconds(45), 4096, 120);
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.EPOCH;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
