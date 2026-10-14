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
    private final com.csd.occ.bunching.domain.SourceRetentionPort sourceRetention;
    private final com.csd.occ.bunching.domain.ConsumerStateRepository consumers;
    private final com.csd.occ.bunching.domain.AlertProcessingStateRepository alerts;

    public BunchingRetentionService(SourceProcessingStateRepository sources, AlertEventRepository events,
                                   DiagnosisOutboxRepository outbox, BunchingRetentionProperties retention, Clock clock,
                                   com.csd.occ.bunching.domain.SourceRetentionPort sourceRetention,
                                   com.csd.occ.bunching.domain.ConsumerStateRepository consumers,
                                   com.csd.occ.bunching.domain.AlertProcessingStateRepository alerts) {
        this.sources = sources;
        this.events = events;
        this.outbox = outbox;
        this.retention = retention;
        this.clock = clock;
        this.sourceRetention = sourceRetention;
        this.consumers = consumers;
        this.alerts = alerts;
    }

    @Transactional
    public void purgeExpiredOperationalState() {
        var cutoff = clock.instant().minus(retention.duration());
        outbox.deleteDeliveredOrTerminalBefore(cutoff);
        events.deleteResolvedBefore(cutoff);
        var checkpoint = consumers.findWithLockById(1).orElseThrow();
        var protectedRuns = events.findProtectedDemoRunIds();
        alerts.findExpiredDemoRunIds(cutoff).stream()
                .filter(run -> !protectedRuns.contains(run))
                .filter(sourceRetention::demoRunIsAbsent).forEach(run -> {
                    alerts.deleteByDemoRunId(run);
                    events.deleteByDemoRunId(run);
                });
        sources.findRetentionCandidates(cutoff).stream()
                .filter(state -> !java.util.Objects.equals(state.demoRunId(), checkpoint.demoRunId())
                        || state.sourceSequence() <= checkpoint.lastSequence())
                .filter(state -> sourceRetention.sourceIsAbsent(state.demoRunId(), state.rawObservationId()))
                .forEach(sources::delete);
    }
}
