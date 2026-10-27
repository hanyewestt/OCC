package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import com.csd.occ.disruption.bunching.domain.AlertEventRepository;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.SourceProcessingStateRepository;
import com.csd.occ.disruption.bunching.infrastructure.config.BunchingRetentionProperties;
import com.csd.occ.disruption.hazard.domain.HazardStateRepository;
import com.csd.occ.disruption.infrastructure.config.HazardProperties;
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
    private final com.csd.occ.disruption.bunching.domain.SourceRetentionPort sourceRetention;
    private final com.csd.occ.disruption.bunching.domain.ConsumerStateRepository consumers;
    private final com.csd.occ.disruption.bunching.domain.AlertProcessingStateRepository alerts;
    private final HazardStateRepository hazardStates;
    private final com.csd.occ.disruption.hazard.domain.HazardFeedHealthRepository hazardFeedHealth;
    private final HazardProperties hazardSettings;

    public BunchingRetentionService(SourceProcessingStateRepository sources, AlertEventRepository events,
                                   DiagnosisOutboxRepository outbox, BunchingRetentionProperties retention, Clock clock,
                                   com.csd.occ.disruption.bunching.domain.SourceRetentionPort sourceRetention,
                                   com.csd.occ.disruption.bunching.domain.ConsumerStateRepository consumers,
                                   com.csd.occ.disruption.bunching.domain.AlertProcessingStateRepository alerts,
                                   HazardStateRepository hazardStates, com.csd.occ.disruption.hazard.domain.HazardFeedHealthRepository hazardFeedHealth,
                                   HazardProperties hazardSettings) {
        this.sources = sources;
        this.events = events;
        this.outbox = outbox;
        this.retention = retention;
        this.clock = clock;
        this.sourceRetention = sourceRetention;
        this.consumers = consumers;
        this.alerts = alerts;
        this.hazardStates = hazardStates;
        this.hazardFeedHealth = hazardFeedHealth;
        this.hazardSettings = hazardSettings;
    }

    @Transactional
    public long purgeExpiredOperationalState() {
        var cutoff = clock.instant().minus(retention.duration());
        long deletedRows = outbox.deleteDeliveredOrTerminalBefore(cutoff);
        deletedRows += events.deleteResolvedBefore(cutoff);
        deletedRows += hazardStates.deleteClearedBefore(clock.instant().minus(hazardSettings.stateRetention()));
        deletedRows += hazardFeedHealth.deleteExpiredSimulationHealth(clock.instant().minus(hazardSettings.stateRetention()));
        var checkpoint = consumers.findWithLockById(1).orElseThrow();
        var protectedRuns = events.findProtectedDemoRunIds();
        for (var run : alerts.findExpiredDemoRunIds(cutoff)) {
            if (protectedRuns.contains(run) || !sourceRetention.demoRunIsAbsent(run)) continue;
            deletedRows += alerts.deleteByDemoRunId(run);
            deletedRows += events.deleteByDemoRunId(run);
        }
        for (var state : sources.findRetentionCandidates(cutoff)) {
            if (java.util.Objects.equals(state.demoRunId(), checkpoint.demoRunId())
                    && state.sourceSequence() > checkpoint.lastSequence()) continue;
            if (!sourceRetention.sourceIsAbsent(state.demoRunId(), state.rawObservationId())) continue;
            sources.delete(state);
            deletedRows++;
        }
        return deletedRows;
    }
}
