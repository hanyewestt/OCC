package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HazardPollingService {
    private final HazardFeedPort provider;
    private final HazardEvidencePort evidence;
    private final PollRunRepository polls;
    private final RawObservationRepository raw;
    private final HazardObservationRepository observations;
    private final FeedStatusRepository statuses;
    private final Clock clock;
    public HazardPollingService(HazardFeedPort provider, HazardEvidencePort evidence, PollRunRepository polls,
            RawObservationRepository raw, HazardObservationRepository observations, FeedStatusRepository statuses, Clock clock) {
        this.provider = provider; this.evidence = evidence; this.polls = polls; this.raw = raw;
        this.observations = observations; this.statuses = statuses; this.clock = clock;
    }

    public void poll(HazardKind kind, Duration staleAfter) {
        String source = kind.name() + "_DATAMALL";
        PollRun run = new PollRun();
        run.setId(UUID.randomUUID()); run.setSource(source); run.setScopeKey(kind.name());
        run.setIdempotencyKey(source + ":" + run.getId()); run.setStartedAt(clock.instant());
        run.setOutcome("RUNNING"); run.setRecordsReceived(0); run.setClassification("OBSERVATION_ONLY");
        polls.save(run);
        try {
            var batch = provider.fetch(kind);
            run.setHttpStatus(batch.httpStatus());
            if (!batch.rawPages().isEmpty()) {
                String payload = evidence.encodePages(batch.rawPages());
                RawObservation value = new RawObservation();
                value.setId(UUID.randomUUID()); value.setPollRunId(run.getId()); value.setSource(source);
                value.setSourceKey(kind.name()); value.setRequestParameters("{}"); value.setPayload(payload);
                value.setPayloadChecksum(evidence.checksum(payload)); value.setFetchedAt(clock.instant());
                value.setIngestedAt(clock.instant()); value.setHttpStatus(batch.httpStatus()); value.setClassification("OBSERVATION_ONLY");
                raw.save(value);
                observations.saveAll(batch.facts().stream().map(fact -> new HazardObservation(value.getId(), run.getId(),
                        null, evidence.checksum(kind + ":" + fact.sourceEventKey()), fact, value.getFetchedAt())).toList());
            }
            run.setRecordsReceived(batch.facts().size());
            run.setOutcome(batch.httpStatus() < 200 || batch.httpStatus() >= 300 ? "FAILED"
                    : batch.errors().isEmpty() ? "SUCCESS" : "PARTIAL");
            if (!batch.errors().isEmpty()) { run.setErrorCode("incomplete_feed"); run.setErrorMessage("Hazard snapshot incomplete"); }
        } catch (RuntimeException failed) {
            run.setOutcome("FAILED"); run.setErrorCode("poll_failed"); run.setErrorMessage("Hazard ingestion failed");
        } finally {
            run.setCompletedAt(clock.instant()); polls.save(run);
            var id = new FeedStatusId(source, kind.name());
            FeedStatus status = statuses.findById(id).orElseGet(FeedStatus::new);
            status.setSource(source); status.setScopeKey(kind.name());
            status.setStatus(switch (run.getOutcome()) { case "SUCCESS" -> "HEALTHY"; case "PARTIAL" -> "DEGRADED"; default -> "FAILED"; });
            status.setLastAttemptAt(run.getStartedAt()); status.setUpdatedAt(run.getCompletedAt());
            status.setStaleAfterSeconds(Math.toIntExact(staleAfter.toSeconds()));
            if ("SUCCESS".equals(run.getOutcome())) { status.setLastSuccessAt(run.getCompletedAt()); status.setConsecutiveFailures(0); }
            else status.setConsecutiveFailures(status.getConsecutiveFailures() == null ? 1 : status.getConsecutiveFailures() + 1);
            status.setMessage("SUCCESS".equals(run.getOutcome()) ? null : "Hazard snapshot unavailable or incomplete");
            statuses.save(status);
        }
    }
}
