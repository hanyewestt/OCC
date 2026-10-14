package com.csd.occ.diagnosis.infrastructure.config;
import com.csd.occ.diagnosis.domain.DiagnosisRetentionService;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class DiagnosisRetentionSchedule {
    private final DiagnosisRetentionService retention;
    private final DiagnosisRetentionProperties properties;
    public DiagnosisRetentionSchedule(DiagnosisRetentionService retention, DiagnosisRetentionProperties properties) {
        this.retention = retention; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${occ.diagnosis.retention.interval}", initialDelayString = "${occ.diagnosis.retention.initial-delay}")
    public void purge() { retention.purge(properties.duration()); }
}
