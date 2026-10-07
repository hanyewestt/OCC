package com.csd.occ.bunching.infrastructure.scheduling;

import com.csd.occ.bunching.domain.AlertEventRepository;
import com.csd.occ.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.bunching.domain.SourceProcessingStateRepository;
import com.csd.occ.bunching.infrastructure.config.BunchingRetentionProperties;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BunchingRetentionService {
    private final SourceProcessingStateRepository sources;
    private final AlertEventRepository events;
    private final DiagnosisOutboxRepository outbox;
    private final BunchingRetentionProperties retention;
    private final Clock clock;

    public BunchingRetentionService(SourceProcessingStateRepository sources, AlertEventRepository events,
                                   DiagnosisOutboxRepository outbox, BunchingRetentionProperties retention, Clock clock) {
        this.sources = sources;
        this.events = events;
        this.outbox = outbox;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public void purgeExpiredOperationalState() {
        var cutoff = clock.instant().minus(retention.duration());
        outbox.deleteDeliveredOrTerminalBefore(cutoff);
        events.deleteResolvedBefore(cutoff);
        sources.deleteCompletedBefore(cutoff);
    }
}
