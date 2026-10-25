package com.csd.occ.planning.advisory.domain;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryReconciliationService {
    private final IssuedInstructionRepository instructions;
    private final AdvisoryProposalRepository proposals;
    private final PublicNoticeRepository notices;
    private final Clock clock;
    public AdvisoryReconciliationService(IssuedInstructionRepository instructions, PublicNoticeRepository notices,
            AdvisoryProposalRepository proposals, Clock clock) {
        this.instructions = instructions; this.notices = notices; this.proposals = proposals; this.clock = clock;
    }
    @Transactional
    public void sourceCleared(UUID id, String reason) {
        instructions.cancelByDisruption(id, "SOURCE_WITHDRAWN");
        if (!"NO_LONGER_REPORTED".equals(reason)) notices.withdrawSource(id, clock.instant());
    }

    @Transactional
    public void runClosed(UUID runId) {
        proposals.expirePendingByDemoRunId(runId);
        instructions.cancelActiveByDemoRunId(runId);
    }
}
