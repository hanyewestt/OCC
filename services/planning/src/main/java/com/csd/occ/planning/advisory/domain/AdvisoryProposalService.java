package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;

@Service
public class AdvisoryProposalService {
    private final AdvisoryProposalRepository proposals;
    private final DiagnosisRequestRepository requests;
    private final AdvisoryModelEvidenceRepository evidence;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;
    public AdvisoryProposalService(AdvisoryProposalRepository proposals, DiagnosisRequestRepository requests,
            AdvisoryModelEvidenceRepository evidence, Clock clock, ApplicationAuditEventRepository auditEvents) {
        this.proposals = proposals; this.requests = requests; this.evidence = evidence; this.clock = clock; this.auditEvents = auditEvents;
    }
    @Transactional
    public void record(UUID requestId, List<AdvisoryProposal> captured) {
        var request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        for (var proposal : captured) if (!proposals.existsByDisruptionIdAndDemoRunIdAndBusIdAndSourceRevisionKeyAndCandidateType(
                proposal.disruptionId(), proposal.demoRunId(), proposal.busId(),
                proposal.sourceRevisionKey(), proposal.candidateType())) {
            proposals.expireSuperseded(proposal.disruptionId(), proposal.demoRunId(), proposal.busId(), proposal.id());
            proposals.save(proposal);
            evidence.save(new AdvisoryModelEvidence(proposal));
            UUID eventId = proposal.id(); var now = clock.instant();
            auditEvents.save(new ApplicationAuditEvent(eventId, proposal.createdAt(), now, "AI_PROCESSING",
                    "ADVISORY_PROPOSAL_GENERATED", "SUCCESS", null, "SYSTEM", "ADVISORY_PROPOSAL",
                    proposal.id().toString(), proposal.demoRunId(), proposal.busId(), null, null,
                    "Advisory proposal generated", java.util.Map.of("model", proposal.model(),
                            "input", proposal.modelInput(), "output", proposal.modelOutput()),
                    "advisory-proposal:" + proposal.id()));
        }
        request.complete(clock.instant());
    }
}
