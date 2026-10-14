package com.csd.occ.disruption.traffic.domain;

import com.csd.occ.disruption.bunching.domain.DisruptionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrafficIncidentSyncService {
    private static final String KIND = "TRAFFIC_INCIDENT";
    private static final String ACTIVE = "ACTIVE";
    private static final String CLEARED = "CLEARED";
    private final TrafficPollCheckpointRepository checkpoints;
    private final TrafficIncidentStateRepository states;
    private final TrafficIncidentEventRepository events;
    private final EventEvidenceRepository evidence;
    private final DisruptionRepository disruptions;
    private final TrafficIncidentEvidencePort evidenceCodec;
    private final Clock clock;

    public TrafficIncidentSyncService(TrafficPollCheckpointRepository checkpoints, TrafficIncidentStateRepository states,
            TrafficIncidentEventRepository events, EventEvidenceRepository evidence, DisruptionRepository disruptions,
            TrafficIncidentEvidencePort evidenceCodec, Clock clock) {
        this.checkpoints = checkpoints; this.states = states; this.events = events; this.evidence = evidence;
        this.disruptions = disruptions; this.evidenceCodec = evidenceCodec; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TrafficPollCursor cursor() {
        return checkpoints.findById(1).map(value -> new TrafficPollCursor(value.completedAt(), value.pollRunId()))
                .orElse(new TrafficPollCursor(null, null));
    }

    @Transactional
    public void process(TrafficIncidentPollPort.TrafficIncidentPoll poll, Duration staleAfter,
            int clearAfterSuccessfulAbsences) {
        if (poll == null || poll.pollRunId() == null || poll.completedAt() == null)
            throw new IllegalArgumentException("Completed traffic poll identity and timestamp are required");
        TrafficPollCheckpoint checkpoint = checkpoints.findForUpdate().orElseThrow(
                () -> new IllegalStateException("Traffic poll checkpoint has not been initialized"));
        if (!isAfterCursor(poll, checkpoint)) return;

        Instant now = clock.instant();
        if (isCompleteAndFresh(poll, now, staleAfter) && hasValidScope(poll) && hasValidFacts(poll))
            processCompletePoll(poll, clearAfterSuccessfulAbsences);

        // The cursor tracks feed consumption, including failed or stale polls. Only complete, fresh polls
        // are allowed to change incident state or count toward an absence-based clear.
        checkpoint.advance(poll.completedAt(), poll.pollRunId());
    }

    private void processCompletePoll(TrafficIncidentPollPort.TrafficIncidentPoll poll, int clearAfterAbsences) {
        Set<String> present = new HashSet<>();
        for (TrafficIncidentPollPort.TrafficIncidentFact fact : poll.incidents()) {
            present.add(fact.sourceIncidentKey());
            observe(poll, fact);
        }
        for (TrafficIncidentState state : activeStates(poll)) {
            if (present.contains(state.sourceIncidentKey())) continue;
            state.markAbsent(poll.completedAt());
            if (state.absenceCount() >= clearAfterAbsences) clear(poll, state);
        }
    }

    private void observe(TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact fact) {
        String hash = snapshotHash(fact);
        TrafficIncidentState state = findState(poll, fact.sourceIncidentKey()).orElse(null);
        Instant at = poll.completedAt();
        if (state == null) {
            UUID disruptionId = UUID.randomUUID();
            state = new TrafficIncidentState(UUID.randomUUID(), disruptionId, poll, fact, hash, at);
            disruptions.recordTransition(disruptionId, KIND, poll.classification(), poll.demoRunId(), ACTIVE,
                    at, at, null);
            states.save(state);
            recordEvent(poll, state.disruptionId(), "OPENED", TrafficIncidentSnapshot.of(fact, poll.pollRunId()));
            return;
        }

        if (!state.isActive()) {
            UUID disruptionId = UUID.randomUUID();
            state.reopen(disruptionId, poll, fact, hash, at);
            disruptions.recordTransition(disruptionId, KIND, poll.classification(), poll.demoRunId(), ACTIVE,
                    at, at, null);
            states.save(state);
            recordEvent(poll, disruptionId, "OPENED", TrafficIncidentSnapshot.of(fact, poll.pollRunId()));
            return;
        }

        if (fact.fetchedAt() != null && state.fetchedAt() != null && !fact.fetchedAt().isAfter(state.fetchedAt())) {
            state.markPresentWithoutChange(poll, fact);
            return;
        }
        if (state.hasSnapshot(hash)) {
            state.markPresentWithoutChange(poll, fact);
            return;
        }

        state.observe(poll, fact, hash, at);
        disruptions.recordTransition(state.disruptionId(), KIND, poll.classification(), poll.demoRunId(), ACTIVE,
                state.openedAt(), at, null);
        states.save(state);
        recordEvent(poll, state.disruptionId(), "UPDATED", TrafficIncidentSnapshot.of(fact, poll.pollRunId()));
    }

    private void clear(TrafficIncidentPollPort.TrafficIncidentPoll poll, TrafficIncidentState state) {
        state.clear(poll.completedAt());
        disruptions.recordTransition(state.disruptionId(), KIND, state.classification(), state.demoRunId(), CLEARED,
                state.openedAt(), poll.completedAt(), poll.completedAt());
        states.save(state);
        recordEvent(poll, state.disruptionId(), "CLEARED", TrafficIncidentSnapshot.of(state));
    }

    private void recordEvent(TrafficIncidentPollPort.TrafficIncidentPoll poll, UUID disruptionId, String action,
            TrafficIncidentSnapshot snapshot) {
        UUID eventId = UUID.randomUUID();
        String eventJson = evidenceCodec.encode(poll, snapshot, action);
        events.save(new TrafficIncidentEvent(eventId, disruptionId, action, poll.classification(), poll.demoRunId(),
                snapshot.sourceObservationId(), poll.pollRunId(), snapshot.fetchedAt() == null ? poll.completedAt() : snapshot.fetchedAt(),
                poll.completedAt(), eventJson));
        evidence.save(new EventEvidence(eventId, snapshot.sourceObservationId(), eventJson, poll.completedAt()));
    }

    private java.util.Optional<TrafficIncidentState> findState(
            TrafficIncidentPollPort.TrafficIncidentPoll poll, String sourceIncidentKey) {
        return poll.demoRunId() == null
                ? states.findByClassificationAndDemoRunIdIsNullAndSourceAndSourceIncidentKey(
                        poll.classification(), poll.source(), sourceIncidentKey)
                : states.findByClassificationAndDemoRunIdAndSourceAndSourceIncidentKey(
                        poll.classification(), poll.demoRunId(), poll.source(), sourceIncidentKey);
    }

    private java.util.List<TrafficIncidentState> activeStates(TrafficIncidentPollPort.TrafficIncidentPoll poll) {
        return poll.demoRunId() == null
                ? states.findByClassificationAndDemoRunIdIsNullAndSourceAndStatus(
                        poll.classification(), poll.source(), ACTIVE)
                : states.findByClassificationAndDemoRunIdAndSourceAndStatus(
                        poll.classification(), poll.demoRunId(), poll.source(), ACTIVE);
    }

    private static boolean isAfterCursor(TrafficIncidentPollPort.TrafficIncidentPoll poll, TrafficPollCheckpoint checkpoint) {
        if (checkpoint.completedAt() == null || checkpoint.pollRunId() == null) return true;
        int timestamp = poll.completedAt().compareTo(checkpoint.completedAt());
        return timestamp > 0 || (timestamp == 0 && poll.pollRunId().compareTo(checkpoint.pollRunId()) > 0);
    }

    private static boolean isCompleteAndFresh(TrafficIncidentPollPort.TrafficIncidentPoll poll, Instant now,
            Duration staleAfter) {
        return poll.complete() && "SUCCESS".equals(poll.outcome())
                && !poll.completedAt().isBefore(now.minus(staleAfter));
    }

    private static boolean hasValidScope(TrafficIncidentPollPort.TrafficIncidentPoll poll) {
        return poll.classification() != null && poll.source() != null
                && (("OBSERVATION_ONLY".equals(poll.classification()) && poll.demoRunId() == null)
                || ("SIMULATION".equals(poll.classification()) && poll.demoRunId() != null));
    }

    private static boolean hasValidFacts(TrafficIncidentPollPort.TrafficIncidentPoll poll) {
        return poll.incidents() != null && poll.incidents().stream().allMatch(fact -> hasValidScope(poll, fact));
    }

    private static boolean hasValidScope(TrafficIncidentPollPort.TrafficIncidentPoll poll,
            TrafficIncidentPollPort.TrafficIncidentFact fact) {
        return fact != null && fact.id() != null && fact.sourceIncidentKey() != null
                && fact.sourceIncidentKey().matches("[0-9a-f]{64}")
                && poll.classification().equals(fact.classification())
                && java.util.Objects.equals(poll.demoRunId(), fact.demoRunId())
                && fact.incidentType() != null && !fact.incidentType().isBlank()
                && fact.message() != null && fact.latitude() != null && fact.longitude() != null
                && fact.latitude().compareTo(java.math.BigDecimal.valueOf(-90)) >= 0
                && fact.latitude().compareTo(java.math.BigDecimal.valueOf(90)) <= 0
                && fact.longitude().compareTo(java.math.BigDecimal.valueOf(-180)) >= 0
                && fact.longitude().compareTo(java.math.BigDecimal.valueOf(180)) <= 0
                && fact.fetchedAt() != null;
    }

    private static String snapshotHash(TrafficIncidentPollPort.TrafficIncidentFact fact) {
        String[] values = { fact.incidentType(), fact.message(), fact.latitude().stripTrailingZeros().toPlainString(),
                fact.longitude().stripTrailingZeros().toPlainString(), fact.observedAt() == null ? null : fact.observedAt().toString() };
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            if (value == null) canonical.append("-1:");
            else canonical.append(value.length()).append(':').append(value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public record TrafficPollCursor(Instant completedAt, UUID pollRunId) { }
}
