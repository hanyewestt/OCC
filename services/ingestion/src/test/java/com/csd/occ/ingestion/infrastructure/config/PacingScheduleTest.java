package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.domain.PacingService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PacingScheduleTest {
    private final PacingService pacing = mock(PacingService.class);
    private final PacingSchedule schedule = new PacingSchedule(pacing, new PacingProperties(Duration.ofMillis(20),
            Duration.ofSeconds(180), 0.5, 1.5, Duration.ofSeconds(180), Duration.ofMinutes(30), 200));

    @Test
    void calculatesOnItsOwnThreadAndKeepsRunningAfterAFailure() {
        AtomicReference<String> thread = new AtomicReference<>();
        when(pacing.refresh()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            throw new IllegalStateException("database unavailable");
        });

        schedule.start();
        try {
            verify(pacing, timeout(2000).atLeast(3)).refresh();
        } finally {
            schedule.stop();
        }

        assertThat(thread.get()).isEqualTo("pacing-schedule");
        assertThat(schedule.isRunning()).isFalse();
    }
}
