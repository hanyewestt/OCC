package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.domain.RetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RetentionScheduleMetricsTest {
    @Test
    void recordsRowsRemovedByScheduledCleanup() {
        RetentionService retention = mock(RetentionService.class);
        when(retention.purgeExpiredObservations()).thenReturn(7L);
        var registry = new SimpleMeterRegistry();

        new RetentionSchedule(retention, registry).purgeExpiredObservations();

        assertThat(registry.get("occ.ingestion.retention.deleted_rows").counter().count()).isEqualTo(7.0);
    }

    @Test
    void leavesTheCounterUnchangedWhenNoRowsWereRemoved() {
        RetentionService retention = mock(RetentionService.class);
        when(retention.purgeExpiredObservations()).thenReturn(0L);
        var registry = new SimpleMeterRegistry();

        new RetentionSchedule(retention, registry).purgeExpiredObservations();

        assertThat(registry.get("occ.ingestion.retention.deleted_rows").counter().count()).isZero();
    }
}
