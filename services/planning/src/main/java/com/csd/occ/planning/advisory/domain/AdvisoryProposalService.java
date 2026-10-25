package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryProposalService {
    private final AdvisoryProposalRepository proposals;
    private final DiagnosisRequestRepository requests;
    private final Clock clock;
    public AdvisoryProposalService(AdvisoryProposalRepository proposals, DiagnosisRequestRepository requests, Clock clock) {
        this.proposals = proposals; this.requests = requests; this.clock = clock;
    }
    @Transactional
    public void record(UUID requestId, List<AdvisoryProposal> captured) {
        var request = requests.findById(requestId).orElseThrow();
        if (request.status() != DiagnosisRequestStatus.PROCESSING) return;
        for (var proposal : captured) if (!proposals.existsById(proposal.id())) {
            proposals.expireSuperseded(proposal.disruptionId(), proposal.demoRunId(), proposal.busId(), proposal.id());
            proposals.save(proposal);
        }
        request.complete(clock.instant());
    }
}
