package com.csd.occ.disruption.domain.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditRetentionService {
    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditMetadataRepository metadata;
    private final Clock clock;
    public ApplicationAuditRetentionService(ApplicationAuditEventRepository events, ApplicationAuditMetadataRepository metadata, Clock clock) {
        this.events = events; this.metadata = metadata; this.clock = clock;
    }
    @Transactional public Instant collectionStartedAt() {
        metadata.initialize(clock.instant());
        return metadata.findById((short) 1).orElseThrow().collectionStartedAt();
    }
    @Transactional public int purgeExpired(Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) throw new IllegalArgumentException("Audit retention must be positive");
        return events.deleteBefore(clock.instant().minus(retention));
    }
}
