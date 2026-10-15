package com.csd.occ.disruption.bunching.infrastructure.scheduling;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class BunchingRetentionSchedule {
    private final BunchingRetentionService retention;
    private final Counter deletedRows;
    public BunchingRetentionSchedule(BunchingRetentionService retention, MeterRegistry meterRegistry) {
        this.retention = retention;
        this.deletedRows = Counter.builder("occ.disruption.retention.deleted_rows")
                .description("Rows removed by scheduled Disruption retention cleanup")
                .register(meterRegistry);
    }
    @Scheduled(initialDelayString = "${occ.disruption.retention.initial-delay}", fixedDelayString = "${occ.disruption.retention.interval}")
    public void purgeExpiredOperationalState() {
        long count = retention.purgeExpiredOperationalState();
        if (count > 0) deletedRows.increment(count);
    }
}
