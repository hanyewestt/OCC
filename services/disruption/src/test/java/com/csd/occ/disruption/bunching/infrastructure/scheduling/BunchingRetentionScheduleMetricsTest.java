package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BunchingRetentionScheduleMetricsTest {
    @Test
    void recordsRowsRemovedByScheduledCleanup() {
        BunchingRetentionService retention = mock(BunchingRetentionService.class);
        when(retention.purgeExpiredOperationalState()).thenReturn(5L);
        var registry = new SimpleMeterRegistry();

        new BunchingRetentionSchedule(retention, registry).purgeExpiredOperationalState();

        assertThat(registry.get("occ.disruption.retention.deleted_rows").counter().count()).isEqualTo(5.0);
    }

    @Test
    void leavesTheCounterUnchangedWhenNoRowsWereRemoved() {
        BunchingRetentionService retention = mock(BunchingRetentionService.class);
        when(retention.purgeExpiredOperationalState()).thenReturn(0L);
        var registry = new SimpleMeterRegistry();

        new BunchingRetentionSchedule(retention, registry).purgeExpiredOperationalState();

        assertThat(registry.get("occ.disruption.retention.deleted_rows").counter().count()).isZero();
    }
}
