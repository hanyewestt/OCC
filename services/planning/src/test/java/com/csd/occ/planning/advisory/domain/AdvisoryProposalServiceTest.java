package com.csd.occ.planning.advisory.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestRepository;
import com.csd.occ.planning.diagnosis.domain.EventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdvisoryProposalServiceTest {
    @Test
    void proposalRecordingCopiesModelEvidenceToIndependentRetentionStore() {
        var requestId = UUID.randomUUID();
        var request = new DiagnosisRequest(requestId, EventType.HAZARD, "disruption", UUID.randomUUID(),
                "planning-event-v1", Instant.EPOCH);
        request.claim(Instant.parse("2026-10-25T04:05:00Z"), Instant.parse("2026-10-25T04:00:00Z"));
        var requests = mock(DiagnosisRequestRepository.class);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        var proposals = mock(AdvisoryProposalRepository.class);
        var evidence = mock(AdvisoryModelEvidenceRepository.class);
        var service = new AdvisoryProposalService(proposals, requests, evidence,
                Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"), ZoneOffset.UTC));
        var candidate = new AdvisoryCandidate(InstructionType.BE_CAUTIOUS,
                new InstructionParameters("44251", "School", 3, null, null, null), "Reported incident");
        var hazard = new AdvisoryContextPort.Hazard(UUID.randomUUID(), "TRAFFIC_INCIDENT", "ACTIVE", null,
                "SIMULATION", UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                new HazardEventFacts.Fact("TRAFFIC_INCIDENT", "incident", "OBSERVED", Instant.EPOCH,
                        null, null, "Incident", "Blocked", null, null, List.of(), null, null, null, null, List.of()),
                List.of(), List.of());
        var bus = new AdvisoryContextPort.Bus("demo-190-b", "demo-190-b", "SBS5898U", "190", 1,
                UUID.randomUUID(), "ASSIGNED", null);
        var proposal = new AdvisoryProposal(UUID.randomUUID(), UUID.randomUUID(), hazard, bus,
                new AdvisoryDraftPort.Result(candidate, "gemini-test", "captured model input", "captured output"),
                "Disruption near School. Be cautious.", Instant.EPOCH);

        service.record(requestId, List.of(proposal));

        verify(evidence).save(any(AdvisoryModelEvidence.class));
    }
}
