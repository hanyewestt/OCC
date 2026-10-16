package com.csd.occ.planning.diagnosis.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DiagnosisRetentionScheduleMetricsTest {
    @Test
    void recordsRowsRemovedByScheduledCleanup() {
        var duration = Duration.ofDays(7);
        DiagnosisRetentionService retention = mock(DiagnosisRetentionService.class);
        when(retention.purge(duration)).thenReturn(4L);
        var properties = new DiagnosisRetentionProperties(duration, Duration.ofHours(1), Duration.ZERO);
        var registry = new SimpleMeterRegistry();

        new DiagnosisRetentionSchedule(retention, properties, registry).purge();

        assertThat(registry.get("occ.planning.retention.deleted_rows").counter().count()).isEqualTo(4.0);
    }

    @Test
    void leavesTheCounterUnchangedWhenNoRowsWereRemoved() {
        var duration = Duration.ofDays(7);
        DiagnosisRetentionService retention = mock(DiagnosisRetentionService.class);
        when(retention.purge(duration)).thenReturn(0L);
        var properties = new DiagnosisRetentionProperties(duration, Duration.ofHours(1), Duration.ZERO);
        var registry = new SimpleMeterRegistry();

        new DiagnosisRetentionSchedule(retention, properties, registry).purge();

        assertThat(registry.get("occ.planning.retention.deleted_rows").counter().count()).isZero();
    }
}
