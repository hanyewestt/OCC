package com.csd.occ.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TrafficIncidentPollingService {
    private static final Logger log = LoggerFactory.getLogger(TrafficIncidentPollingService.class);
    private static final String SOURCE = "TRAFFIC_DATAMALL";
    private static final String SCOPE = "traffic-incidents";
    private static final String REQUEST_PARAMETERS = "{\"endpoint\":\"TrafficIncidents\"}";

    private final TrafficIncidentFeedPort provider;
    private final PollRunRepository pollRuns;
    private final RawObservationRepository rawObservations;
    private final TrafficIncidentObservationRepository incidents;
    private final Clock clock;

    public TrafficIncidentPollingService(TrafficIncidentFeedPort provider, PollRunRepository pollRuns,
            RawObservationRepository rawObservations, TrafficIncidentObservationRepository incidents, Clock clock) {
        this.provider = provider;
        this.pollRuns = pollRuns;
        this.rawObservations = rawObservations;
        this.incidents = incidents;
        this.clock = clock;
    }

    public void pollOnce() {
        Instant startedAt = clock.instant();
        UUID pollId = UUID.randomUUID();
        PollRun run = new PollRun();
        run.setId(pollId);
        run.setSource(SOURCE);
        run.setScopeKey(SCOPE);
        run.setIdempotencyKey("traffic-datamall-" + pollId);
        run.setStartedAt(startedAt);
        run.setOutcome("RUNNING");
        run.setRecordsReceived(0);
        run.setClassification("OBSERVATION_ONLY");
        pollRuns.save(run);

        try {
            TrafficIncidentFeedPort.TrafficIncidentBatch batch = provider.trafficIncidents();
            run.setHttpStatus(batch.httpStatus());
            if (batch.httpStatus() < 200 || batch.httpStatus() >= 300) {
                run.setOutcome("FAILED");
                run.setErrorCode(String.valueOf(batch.httpStatus()));
                run.setErrorMessage("DataMall traffic incident request failed");
            } else if (batch.payload() == null) {
                run.setOutcome("PARTIAL");
                run.setErrorCode("invalid_response");
                run.setErrorMessage("DataMall traffic incident response could not be parsed");
            } else {
                persist(batch, pollId);
                run.setRecordsReceived(batch.incidents().size());
                run.setOutcome(batch.errors().isEmpty() ? "SUCCESS" : "PARTIAL");
                if (!batch.errors().isEmpty()) {
                    run.setErrorCode("incomplete_feed");
                    run.setErrorMessage("DataMall traffic incident poll did not produce a complete snapshot");
                }
            }
        } catch (RuntimeException failure) {
            run.setOutcome("FAILED");
            run.setErrorCode("poll_failed");
            run.setErrorMessage("DataMall traffic incident poll failed");
            log.error("DataMall traffic incident poll failed; incomplete data will not clear detected incidents.");
        } finally {
            run.setCompletedAt(clock.instant());
            pollRuns.save(run);
        }
    }

    private void persist(TrafficIncidentFeedPort.TrafficIncidentBatch batch, UUID pollId) {
        Instant fetchedAt = clock.instant();
        RawObservation raw = new RawObservation();
        raw.setId(UUID.randomUUID());
        raw.setPollRunId(pollId);
        raw.setSource(SOURCE);
        raw.setSourceKey(SCOPE);
        raw.setRequestParameters(REQUEST_PARAMETERS);
        raw.setPayload(batch.payload());
        raw.setPayloadChecksum(sha256(batch.payload()));
        raw.setFetchedAt(fetchedAt);
        raw.setIngestedAt(fetchedAt);
        raw.setHttpStatus(batch.httpStatus());
        raw.setClassification("OBSERVATION_ONLY");
        rawObservations.save(raw);

        List<TrafficIncidentObservation> normalized = batch.incidents().stream()
                .map(incident -> new TrafficIncidentObservation(raw.getId(), pollId, incident.sourceIncidentKey(),
                        incident.incidentType(), incident.latitude(), incident.longitude(), incident.message(),
                        incident.observedAt(), fetchedAt, fetchedAt))
                .toList();
        incidents.saveAll(normalized);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
