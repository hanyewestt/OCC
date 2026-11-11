package com.csd.occ.planning.advisory.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;

@Service
public class AdvisoryRetentionService {
    private final AdvisoryProposalRepository proposals;
    private final IssuedInstructionRepository instructions;
    private final PublicNoticeRepository notices;
    private final InstructionService instructionAudit;
    private final ApplicationAuditEventRepository auditEvents;
    private final Clock clock;
    private final AssignmentBusGuards guards;
    public AdvisoryRetentionService(AdvisoryProposalRepository proposals, IssuedInstructionRepository instructions,
            PublicNoticeRepository notices, InstructionService instructionAudit,
            ApplicationAuditEventRepository auditEvents, Clock clock, AssignmentBusGuards guards) {
        this.proposals = proposals; this.instructions = instructions; this.notices = notices;
        this.instructionAudit = instructionAudit; this.auditEvents = auditEvents; this.clock = clock; this.guards = guards;
    }
    @Transactional
    public long purge(Duration retention) {
        var now = clock.instant();
        var cutoff = now.minus(retention);
        var keys = new java.util.ArrayList<AssignmentBusGuards.Key>();
        instructions.findDueKeys(now).forEach(key -> keys.add(new AssignmentBusGuards.Key(key.getDemoRunId(), key.getBusId())));
        instructions.findInactiveKeysBefore(cutoff).forEach(key -> keys.add(new AssignmentBusGuards.Key(key.getDemoRunId(), key.getBusId())));
        proposals.findUnreferencedKeysBefore(cutoff).forEach(key -> keys.add(new AssignmentBusGuards.Key(key.getDemoRunId(), key.getBusId())));
        guards.lockAll(keys);
        for (var instruction : instructions.findDueForUpdate(now)) {
            instruction.expire("VALIDITY_EXPIRED");
            instructionAudit.recordTransition(instruction, "INSTRUCTION_EXPIRED", null);
        }
        for (var proposal : proposals.findUnreferencedBeforeForUpdate(cutoff)) {
            if (!"PENDING".equals(proposal.status())) continue;
            proposal.expire();
            String sourceKey = "advisory-proposal-state:" + proposal.id() + ":EXPIRED:RETENTION_EXPIRED";
            if (!auditEvents.existsBySourceKey(sourceKey)) {
                var details = new java.util.LinkedHashMap<String, Object>();
                details.put("status", proposal.status());
                details.put("candidate", instructionAudit.candidateSnapshot(proposal.candidate()));
                details.put("reason", "RETENTION_EXPIRED");
                auditEvents.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        now, now, "ADVISORIES", "ADVISORY_PROPOSAL_EXPIRED", "EXPIRED", null, "SYSTEM",
                        "ADVISORY_PROPOSAL", proposal.id().toString(), proposal.demoRunId(), proposal.busId(), null,
                        proposal.eventId() == null ? null : proposal.eventId().toString(), "Pending proposal expired by retention",
                        details, sourceKey));
            }
        }
        long count = instructions.deleteInactiveBefore(cutoff);
        count += proposals.deleteUnreferencedBefore(cutoff);
        count += notices.deleteExpiredBefore(cutoff);
        return count;
    }
}
