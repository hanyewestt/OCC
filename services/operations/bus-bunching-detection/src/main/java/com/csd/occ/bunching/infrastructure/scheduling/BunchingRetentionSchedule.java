package com.csd.occ.bunching.infrastructure.scheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class BunchingRetentionSchedule {
    private final BunchingRetentionService retention;
    public BunchingRetentionSchedule(BunchingRetentionService retention) { this.retention = retention; }
    @Scheduled(initialDelayString = "${occ.bunching.retention.initial-delay}", fixedDelayString = "${occ.bunching.retention.interval}")
    public void purgeExpiredOperationalState() { retention.purgeExpiredOperationalState(); }
}
