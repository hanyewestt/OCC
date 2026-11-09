package com.csd.occ.planning.advisory.infrastructure.config;

import com.csd.occ.planning.advisory.domain.AdvisoryRetentionService;
import com.csd.occ.planning.advisory.domain.InstructionRunReconciliationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdvisoryRetentionSchedule {
    private final AdvisoryRetentionService retention;
    private final InstructionRunReconciliationService instructions;
    private final AdvisoryProperties properties;
    public AdvisoryRetentionSchedule(AdvisoryRetentionService retention, InstructionRunReconciliationService instructions,
            AdvisoryProperties properties) {
        this.retention = retention; this.instructions = instructions; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${occ.diagnosis.retention.interval}", initialDelayString = "${occ.diagnosis.retention.initial-delay}")
    public void purge() {
        retention.purge(properties.retention());
        instructions.reconcileClosedRuns();
    }
}
