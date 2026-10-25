package com.csd.occ.disruption.hazard.domain;

import com.csd.occ.disruption.bunching.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HazardSyncService {
    private final HazardCheckpointRepository checkpoints;
    private final HazardStateRepository hazards;
    private final DisruptionRepository disruptions;
    private final DiagnosisOutboxRepository outbox;
    private final PlanningEventSubmissionPort codec;
    private final Clock clock;
    public HazardSyncService(HazardCheckpointRepository checkpoints, HazardStateRepository hazards,
            DisruptionRepository disruptions, DiagnosisOutboxRepository outbox, PlanningEventSubmissionPort codec, Clock clock) {
        this.checkpoints = checkpoints; this.hazards = hazards; this.disruptions = disruptions;
        this.outbox = outbox; this.codec = codec; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public HazardCheckpoint cursor(String kind) { return checkpoints.findById(kind).orElseThrow(); }

    @Transactional
    public void process(String kind, HazardPollPort.Poll poll, Duration staleAfter, int absenceLimit, Duration absencePeriod) {
        if (poll == null || poll.pollRunId() == null || poll.completedAt() == null || !kind.equals(poll.scope())
                || !("OBSERVATION_ONLY".equals(poll.classification()) && poll.demoRunId() == null
                    || "SIMULATION".equals(poll.classification()) && poll.demoRunId() != null))
            throw new IllegalArgumentException("Invalid hazard poll identity or scope");
        HazardCheckpoint cursor = checkpoints.findForUpdate(kind).orElseThrow();
        if (!cursor.isBefore(poll)) return;
        Instant now = clock.instant();
        if (poll.complete() && "SUCCESS".equals(poll.outcome()) && !poll.completedAt().isBefore(now.minus(staleAfter))) {
            var scoped = new java.util.ArrayList<>(hazards.findScope(poll.source(), poll.scope(), poll.classification(), poll.demoRunId()));
            var cancellations = poll.observations().stream().map(HazardPollPort.Observation::facts)
                    .filter(Objects::nonNull).filter(f -> "CANCEL".equals(f.sourceAction()))
                    .flatMap(f -> f.references().stream()).collect(java.util.stream.Collectors.toSet());
            var present = new HashSet<String>();
            for (var observation : poll.observations()) {
                var facts = observation.facts();
                if (observation.id() == null || observation.fetchedAt() == null || facts == null || !kind.equals(facts.kind())
                        || facts.sourceEventKey() == null || facts.references() == null || facts.locations() == null)
                    throw new IllegalArgumentException("Invalid hazard observation");
                present.add(facts.sourceEventKey());
                if ("CANCEL".equals(facts.sourceAction())) {
                    scoped.stream().filter(h -> facts.references().contains(h.sourceEventKey()))
                            .filter(h -> !"CLEARED".equals(h.status())).forEach(h -> clear(poll, h, "SOURCE_CANCELLED", now));
                    continue;
                }
                if ("RECOVERED".equals(facts.sourceAction())) {
                    scoped.stream().filter(h -> facts.sourceEventKey().equals("network") || h.sourceEventKey().equals(facts.sourceEventKey()))
                            .filter(h -> !"CLEARED".equals(h.status())).forEach(h -> clear(poll, h, "SOURCE_RECOVERED", now));
                    continue;
                }
                var existing = scoped.stream().filter(h -> h.sourceEventKey().equals(facts.sourceEventKey())).findFirst();
                if (cancellations.contains(facts.sourceEventKey())) continue;
                if (existing.isPresent() && "CLEARED".equals(existing.get().status())
                        && ("SOURCE_CANCELLED".equals(existing.get().inactiveReason())
                        || "SOURCE_EXPIRED".equals(existing.get().inactiveReason()) && Objects.equals(existing.get().facts(), facts))) continue;
                if (existing.isEmpty()) {
                    var created = new HazardState(poll, observation, now);
                    record(poll, created, "OPENED", now); hazards.save(created); scoped.add(created);
                } else {
                    var state = existing.get();
                    if (!"CLEARED".equals(state.status()) && Objects.equals(state.facts(), facts)) state.markSeen(poll.completedAt());
                    else {
                        state.observe(observation, poll.completedAt(), now);
                        record(poll, state, "UPDATED", now);
                    }
                }
            }
            if (kind.equals("ROADWORKS")) for (var state : scoped) {
                if (!"CLEARED".equals(state.status()) && !present.contains(state.sourceEventKey())) {
                    state.markAbsent(poll.completedAt());
                    if (state.absenceCount() >= absenceLimit && !poll.completedAt().isBefore(state.firstAbsentAt().plus(absencePeriod)))
                        clear(poll, state, "NO_LONGER_REPORTED", now);
                }
            }
            for (var state : scoped) {
                if (!"CLEARED".equals(state.status()) && state.facts().effectiveUntil() != null && !now.isBefore(state.facts().effectiveUntil()))
                    clear(poll, state, "SOURCE_EXPIRED", now);
                else if ("SCHEDULED".equals(state.status()) && !now.isBefore(state.facts().effectiveFrom())) {
                    state.activate(now); record(poll, state, "UPDATED", now);
                }
            }
        }
        cursor.advance(poll, staleAfter);
    }

    private void clear(HazardPollPort.Poll poll, HazardState state, String reason, Instant now) {
        state.clear(reason, now); record(poll, state, "CLEARED", now);
    }
    private void record(HazardPollPort.Poll poll, HazardState state, String action, Instant now) {
        disruptions.recordTransition(state.disruptionId(), state.kind(), state.classification(), state.demoRunId(), state.status(),
                state.openedAt(), now, "CLEARED".equals(state.status()) ? now : null);
        UUID eventId = UUID.randomUUID();
        var encoded = codec.encodeHazard(new HazardEventSnapshot(eventId, state.disruptionId(), action, state.inactiveReason(),
                state.classification(), state.demoRunId(), state.sourceObservationId(), poll.pollRunId(), now, state.facts()));
        hazards.recordEvent(eventId, state.disruptionId(), state.kind(), action, state.classification(), state.demoRunId(),
                state.sourceObservationId(), poll.pollRunId(), state.lastSeenAt(), now, encoded.payloadJson());
        outbox.save(new DiagnosisOutbox(eventId, eventId, encoded.payloadJson(), encoded.payloadFingerprint(), encoded.schemaVersion(), now));
    }
}
