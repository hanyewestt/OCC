package com.csd.occ.planning.advisory.infrastructure.config;

import com.csd.occ.planning.advisory.domain.AdvisoryRetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdvisoryRetentionSchedule {
    private final AdvisoryRetentionService retention;
    private final AdvisoryProperties properties;
    private final MeterRegistry metrics;
    public AdvisoryRetentionSchedule(AdvisoryRetentionService retention, AdvisoryProperties properties, MeterRegistry metrics) {
        this.retention = retention; this.properties = properties; this.metrics = metrics;
    }
    @Scheduled(fixedDelayString = "${occ.diagnosis.retention.interval}", initialDelayString = "${occ.diagnosis.retention.initial-delay}")
    public void purge() {
        metrics.counter("occ.planning.advisory.retention.deleted_rows").increment(retention.purge(properties.retention()));
    }
}
