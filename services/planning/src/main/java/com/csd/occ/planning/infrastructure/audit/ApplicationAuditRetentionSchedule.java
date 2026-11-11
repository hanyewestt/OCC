package com.csd.occ.planning.infrastructure.audit;

import com.csd.occ.planning.domain.audit.ApplicationAuditRetentionService;
import com.csd.occ.planning.infrastructure.config.AuditRetentionProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApplicationAuditRetentionSchedule {
    private final ApplicationAuditRetentionService retention;
    private final AuditRetentionProperties properties;
    public ApplicationAuditRetentionSchedule(ApplicationAuditRetentionService retention, AuditRetentionProperties properties) {
        this.retention = retention; this.properties = properties;
    }
    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purge() { retention.collectionStartedAt(); retention.purgeExpired(properties.retention()); }
}
