package com.csd.occ.planning.diagnosis.infrastructure.config;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRetentionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class DiagnosisRetentionSchedule {
    private final DiagnosisRetentionService retention;
    private final DiagnosisRetentionProperties properties;
    private final Counter deletedRows;
    public DiagnosisRetentionSchedule(DiagnosisRetentionService retention, DiagnosisRetentionProperties properties,
            MeterRegistry meterRegistry) {
        this.retention = retention;
        this.properties = properties;
        this.deletedRows = Counter.builder("occ.planning.retention.deleted_rows")
                .description("Rows removed by scheduled Planning retention cleanup")
                .register(meterRegistry);
    }
    @Scheduled(fixedDelayString = "${occ.diagnosis.retention.interval}", initialDelayString = "${occ.diagnosis.retention.initial-delay}")
    public void purge() {
        long count = retention.purge(properties.duration());
        if (count > 0) deletedRows.increment(count);
    }
}
