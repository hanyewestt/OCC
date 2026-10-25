package com.csd.occ.planning.advisory.domain;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InstructionRunReconciliationService {
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("RUNNING", "REPLAY_COMPLETE");
    private final IssuedInstructionRepository instructions;
    private final AdvisoryContextPort context;
    private final AdvisoryReconciliationService reconciliation;

    public InstructionRunReconciliationService(IssuedInstructionRepository instructions, AdvisoryContextPort context,
            AdvisoryReconciliationService reconciliation) {
        this.instructions = instructions;
        this.context = context;
        this.reconciliation = reconciliation;
    }

    public void reconcileClosedRuns() {
        for (var runId : instructions.findActiveDemoRunIds())
            if (!ACTIVE_RUN_STATUSES.contains(context.fleet(runId).status())) reconciliation.runClosed(runId);
    }
}
