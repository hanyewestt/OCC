package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoHazardReplayService {
    private final PollRunRepository polls;
    private final RawObservationRepository raw;
    private final HazardObservationRepository hazards;
    private final HazardEvidencePort evidence;
    public DemoHazardReplayService(PollRunRepository polls, RawObservationRepository raw,
            HazardObservationRepository hazards, HazardEvidencePort evidence) {
        this.polls = polls; this.raw = raw; this.hazards = hazards; this.evidence = evidence;
    }
    @Transactional
    public void emit(DemoRun run, int step, Instant now) {
        for (var fact : DemoHazardScenario.observations(step, run.startedAt(), now)) {
            String source = fact.kind().name() + "_DEMO";
            String key = "demo:" + run.id() + ":" + fact.kind() + ":" + step;
            if (polls.findByIdempotencyKey(key).isPresent()) continue;
            PollRun poll = new PollRun();
            poll.setId(UUID.randomUUID()); poll.setSource(source); poll.setScopeKey(fact.kind().name());
            poll.setIdempotencyKey(key); poll.setStartedAt(now); poll.setCompletedAt(now); poll.setOutcome("SUCCESS");
            poll.setRecordsReceived(1); poll.setHttpStatus(200); poll.setClassification("SIMULATION"); poll.setDemoRunId(run.id());
            polls.save(poll);
            String payload = evidence.encodePages(List.of(evidence.encodeFact(fact)));
            RawObservation value = new RawObservation();
            value.setId(UUID.randomUUID()); value.setPollRunId(poll.getId()); value.setSource(source);
            value.setSourceKey(key); value.setRequestParameters("{}"); value.setPayload(payload);
            value.setPayloadChecksum(evidence.checksum(payload)); value.setFetchedAt(now); value.setIngestedAt(now);
            value.setHttpStatus(200); value.setClassification("SIMULATION"); value.setDemoRunId(run.id()); raw.save(value);
            hazards.save(new HazardObservation(value.getId(), poll.getId(), run.id(),
                    evidence.checksum(fact.kind() + ":" + fact.sourceEventKey()), fact, now));
        }
    }
}
