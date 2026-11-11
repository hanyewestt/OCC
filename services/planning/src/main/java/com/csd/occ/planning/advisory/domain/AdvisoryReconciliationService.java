package com.csd.occ.planning.advisory.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashMap;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryReconciliationService {
    private final IssuedInstructionRepository instructions;
    private final AdvisoryProposalRepository proposals;
    private final PublicNoticeService notices;
    private final InstructionService instructionAudit;
    private final ApplicationAuditEventRepository auditEvents;
    private final Clock clock;
    public AdvisoryReconciliationService(IssuedInstructionRepository instructions, PublicNoticeService notices,
            AdvisoryProposalRepository proposals, InstructionService instructionAudit,
            ApplicationAuditEventRepository auditEvents, Clock clock) {
        this.instructions = instructions; this.notices = notices; this.proposals = proposals;
        this.instructionAudit = instructionAudit; this.auditEvents = auditEvents; this.clock = clock;
    }
    @Transactional
    public void sourceCleared(UUID id, String reason) {
        for (var instruction : instructions.findActiveByDisruptionIdForUpdate(id)) {
            instruction.cancel("SOURCE_WITHDRAWN");
            instructionAudit.recordTransition(instruction, "INSTRUCTION_CANCELLED", null);
        }
        if (!"NO_LONGER_REPORTED".equals(reason)) notices.withdrawSource(id);
    }

    @Transactional
    public void runClosed(UUID runId) {
        Instant now = clock.instant();
        for (var proposal : proposals.findPendingByDemoRunIdForUpdate(runId)) {
            proposal.expire();
            String sourceKey = "advisory-proposal-state:" + proposal.id() + ":EXPIRED";
            if (!auditEvents.existsBySourceKey(sourceKey)) {
                var details = new LinkedHashMap<String, Object>();
                details.put("status", proposal.status()); details.put("candidate", instructionAudit.candidateSnapshot(proposal.candidate()));
                details.put("finalCandidate", instructionAudit.candidateSnapshot(proposal.finalCandidate())); details.put("reason", "RUN_CLOSED");
                auditEvents.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        now, now, "ADVISORIES", "ADVISORY_PROPOSAL_EXPIRED", "EXPIRED", null, "SYSTEM",
                        "ADVISORY_PROPOSAL", proposal.id().toString(), proposal.demoRunId(), proposal.busId(), null,
                        proposal.eventId() == null ? null : proposal.eventId().toString(), "Pending proposal expired when run closed",
                        details, sourceKey));
            }
        }
        for (var instruction : instructions.findActiveByDemoRunIdForUpdate(runId)) {
            instruction.cancel("RUN_CLOSED");
            instructionAudit.recordTransition(instruction, "INSTRUCTION_CANCELLED", null);
        }
    }
}
