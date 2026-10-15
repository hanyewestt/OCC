package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.RetentionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionSchedule {
    private final RetentionService retention;
    private final Counter deletedRows;

    public RetentionSchedule(RetentionService retention, MeterRegistry meterRegistry) {
        this.retention = retention;
        this.deletedRows = Counter.builder("occ.ingestion.retention.deleted_rows")
                .description("Rows removed by scheduled Ingestion retention cleanup")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${occ.ingestion.schedule.retention-initial-delay}",
            fixedDelayString = "${occ.ingestion.schedule.retention-interval}")
    public void purgeExpiredObservations() {
        long count = retention.purgeExpiredObservations();
        if (count > 0) deletedRows.increment(count);
    }
}
