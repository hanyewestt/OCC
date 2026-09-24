package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.RetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionSchedule {
    private final RetentionService retention;

    public RetentionSchedule(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void purgeExpiredObservations() {
        retention.purgeExpiredObservations();
    }
}
