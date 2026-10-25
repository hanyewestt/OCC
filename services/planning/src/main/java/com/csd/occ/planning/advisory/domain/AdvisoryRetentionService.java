package com.csd.occ.planning.advisory.domain;

import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryRetentionService {
    private final AdvisoryProposalRepository proposals;
    private final IssuedInstructionRepository instructions;
    private final PublicNoticeRepository notices;
    private final Clock clock;
    public AdvisoryRetentionService(AdvisoryProposalRepository proposals, IssuedInstructionRepository instructions,
            PublicNoticeRepository notices, Clock clock) {
        this.proposals = proposals; this.instructions = instructions; this.notices = notices; this.clock = clock;
    }
    @Transactional
    public long purge(Duration retention) {
        var now = clock.instant();
        var cutoff = now.minus(retention);
        instructions.expireDue(now);
        long count = instructions.deleteInactiveBefore(cutoff);
        count += proposals.deleteUnreferencedBefore(cutoff);
        count += notices.deleteExpiredBefore(cutoff);
        return count;
    }
}
