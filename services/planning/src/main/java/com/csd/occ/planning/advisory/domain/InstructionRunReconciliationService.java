package com.csd.occ.planning.advisory.domain;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InstructionRunReconciliationService {
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("RUNNING", "REPLAY_COMPLETE");
    private final IssuedInstructionRepository instructions;
    private final AdvisoryContextPort context;
    private final AdvisoryReconciliationService reconciliation;
    private final AdvisoryProposalRepository proposals;

    public InstructionRunReconciliationService(IssuedInstructionRepository instructions, AdvisoryContextPort context,
            AdvisoryReconciliationService reconciliation, AdvisoryProposalRepository proposals) {
        this.instructions = instructions;
        this.context = context;
        this.reconciliation = reconciliation;
        this.proposals = proposals;
    }

    public void reconcileClosedRuns() {
        var runIds = new java.util.TreeSet<java.util.UUID>();
        runIds.addAll(instructions.findActiveDemoRunIds());
        runIds.addAll(proposals.findPendingDemoRunIds());
        for (var runId : runIds)
            if (!ACTIVE_RUN_STATUSES.contains(context.fleet(runId).status())) reconciliation.runClosed(runId);
    }
}
