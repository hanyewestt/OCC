package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.ApplicationAuditRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApplicationAuditRetentionSchedule {
    private final ApplicationAuditRetentionService retention;
    private final AuditRetentionProperties properties;

    public ApplicationAuditRetentionSchedule(ApplicationAuditRetentionService retention, AuditRetentionProperties properties) {
        this.retention = retention;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purge() {
        retention.collectionStartedAt();
        retention.purgeExpired(properties.retention());
    }
}
