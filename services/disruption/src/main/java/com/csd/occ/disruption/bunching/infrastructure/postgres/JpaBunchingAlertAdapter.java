package com.csd.occ.disruption.bunching.infrastructure.postgres;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.AlertEventRepository;
import com.csd.occ.disruption.bunching.domain.AlertProcessingState;
import com.csd.occ.disruption.bunching.domain.AlertProcessingStateRepository;
import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutbox;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.DisruptionRepository;
import com.csd.occ.disruption.bunching.domain.BunchingAlertPolicy;
import com.csd.occ.disruption.bunching.domain.BunchingAlertPort;
import com.csd.occ.disruption.bunching.domain.BunchingEvaluation;
import com.csd.occ.disruption.bunching.domain.BunchingEvidencePort;
import com.csd.occ.disruption.bunching.infrastructure.config.BunchingConfirmationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaBunchingAlertAdapter implements BunchingAlertPort {
    private static final int UNSPECIFIED_DIRECTION = 0;
    private final AlertProcessingStateRepository states;
    private final AlertEventRepository events;
    private final BunchingEvidencePort evidence;
    private final Clock clock;
    private final ConsumerSelectionService selection;
    private final DiagnosisOutboxRepository diagnosisOutbox;
    private final DisruptionRepository disruptions;
    private final BunchingConfirmationProperties confirmation;
    private final BunchingAlertPolicy policy = new BunchingAlertPolicy();

    public JpaBunchingAlertAdapter(AlertProcessingStateRepository states, AlertEventRepository events,
                                   BunchingEvidencePort evidence, Clock clock, ConsumerSelectionService selection,
                                   DiagnosisOutboxRepository diagnosisOutbox,
                                   BunchingConfirmationProperties confirmation, DisruptionRepository disruptions) {
        this.states = states;
        this.events = events;
        this.evidence = evidence;
        this.clock = clock;
        this.selection = selection;
        this.diagnosisOutbox = diagnosisOutbox;
        this.confirmation = confirmation;
        this.disruptions = disruptions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void trigger(BunchingEvaluation evaluation) {
        var selected = selection.current();
        if ("SIMULATION".equals(evaluation.snapshot().classification())) {
            if ("DEMO".equals(selected.mode())
                    && evaluation.snapshot().demoRunId().equals(selected.demoRunId()))
                triggerDemo(evaluation, selected.demoRunId());
            return;
        }
        if (!"LIVE".equals(selected.mode())) return;
        evaluation.groups().stream()
                .sorted(Comparator.comparing((BunchingEvaluation.GroupEvaluation group) -> group.group().serviceId())
                        .thenComparing(group -> group.group().stopCode())
                        .thenComparingInt(group -> group.group().directionId().orElse(UNSPECIFIED_DIRECTION)))
                .forEach(result -> triggerLive(evaluation, result));
    }

    private void triggerLive(BunchingEvaluation evaluation, BunchingEvaluation.GroupEvaluation result) {
        if (result.reason() == BunchingEvaluation.Reason.STALE_SNAPSHOT
                || result.reason() == BunchingEvaluation.Reason.FUTURE_SNAPSHOT) return;
        int direction = result.group().directionId().orElse(UNSPECIFIED_DIRECTION);
        String service = result.group().serviceId(); String stop = result.group().stopCode();
        states.insertIfAbsent(UUID.randomUUID(), null, service, stop, direction, Instant.EPOCH);
        AlertProcessingState previous = states.findWithLock(null, service, stop, direction).orElseThrow();
        Instant fetchedAt = evaluation.snapshot().fetchedAt();
        if (!fetchedAt.isAfter(previous.lastFetchedAt())) return;
        BunchingAlertPolicy.Transition transition = policy.transition(result.state(),
                Optional.ofNullable(previous.activeAlertId()));
        UUID episode = transition == BunchingAlertPolicy.Transition.OPENED ? UUID.randomUUID() : previous.activeAlertId();
        if (transition != BunchingAlertPolicy.Transition.NONE) {
            AlertEvent event = saveEvent(new AlertEvent(episode, transition.name(), service, stop, direction,
                    evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId(), fetchedAt, clock.instant(),
                    evidence.encodeAlert(evaluation, result)));
            events.notifyListeners(event.id().toString());
        }
        previous.update(fetchedAt, transition == BunchingAlertPolicy.Transition.CLEARED ? null : episode);
    }

    private void triggerDemo(BunchingEvaluation evaluation, UUID runId) {
        evaluation.groups().stream()
                .sorted(Comparator.comparing((BunchingEvaluation.GroupEvaluation group) -> group.group().serviceId())
                        .thenComparing(group -> group.group().stopCode())
                        .thenComparingInt(group -> group.group().directionId().orElse(UNSPECIFIED_DIRECTION)))
                .forEach(result -> triggerDemo(evaluation, result, runId));
    }

    private void triggerDemo(BunchingEvaluation evaluation, BunchingEvaluation.GroupEvaluation result, UUID runId) {
        int direction = result.group().directionId().orElse(UNSPECIFIED_DIRECTION);
        String service = result.group().serviceId(); String stop = result.group().stopCode();
        states.insertIfAbsent(UUID.randomUUID(), runId, service, stop, direction, Instant.EPOCH);
        AlertProcessingState previous = states.findWithLock(runId, service, stop, direction).orElseThrow();
        Instant fetchedAt = evaluation.snapshot().fetchedAt();
        if (!fetchedAt.isAfter(previous.lastFetchedAt())) return;
        if (result.reason() == BunchingEvaluation.Reason.STALE_SNAPSHOT
                || result.reason() == BunchingEvaluation.Reason.FUTURE_SNAPSHOT) {
            previous.update(fetchedAt, previous.activeAlertId(), previous.leadingObservationId(), previous.followingObservationId());
            previous.clearCandidate();
            return;
        }

        if (previous.activeAlertId() == null) {
            if (result.state() != BunchingEvaluation.State.BUNCHING) {
                previous.update(fetchedAt, null, null, null);
                previous.clearCandidate();
                return;
            }
            BunchingEvaluation.Headway current = result.headways().stream()
                    .filter(BunchingEvaluation.Headway::isBunched).findFirst().orElse(null);
            if (current == null || current.gap().toSeconds() < 0 || current.gap().toSeconds() > Integer.MAX_VALUE) {
                previous.update(fetchedAt, null, null, null);
                previous.clearCandidate();
                return;
            }
            if (previous.pendingHeadwaySeconds() == null || previous.pendingDetectedAt() == null) {
                previous.recordCandidate(fetchedAt, Math.toIntExact(current.gap().toSeconds()),
                        current.leadingObservationId(), current.followingObservationId(),
                        evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId());
                return;
            }

            UUID episode = UUID.randomUUID();
            Instant confirmedAt = clock.instant();
            int secondHeadway = Math.toIntExact(current.gap().toSeconds());
            AlertEvent event = saveEvent(new AlertEvent(episode, "OPENED", service, stop, direction,
                    evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId(), fetchedAt, confirmedAt,
                    evidence.encodeAlert(evaluation, result), "SIMULATION", runId,
                    current.leadingObservationId(), current.followingObservationId(), confirmation.expectedHeadwaySeconds(),
                    previous.pendingHeadwaySeconds(), secondHeadway, previous.pendingDetectedAt(), fetchedAt,
                    previous.pendingLeadingObservationId(), previous.pendingFollowingObservationId(),
                    current.leadingObservationId(), current.followingObservationId(), "bunching-gap-v1"));
            events.notifyListeners(event.id().toString());
            diagnosisOutbox.save(new DiagnosisOutbox(UUID.randomUUID(), event.id(), confirmedAt));
            previous.update(fetchedAt, episode, current.leadingObservationId(), current.followingObservationId());
            previous.clearCandidate();
            return;
        }

        BunchingAlertPolicy.Transition transition = policy.transition(result.state(), Optional.of(previous.activeAlertId()));
        UUID episode = previous.activeAlertId();
        if (transition == BunchingAlertPolicy.Transition.CLEARED) {
            AlertEvent event = saveEvent(new AlertEvent(episode, transition.name(), service, stop, direction,
                    evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId(), fetchedAt, clock.instant(),
                    evidence.encodeAlert(evaluation, result), "SIMULATION", runId, previous.leadingObservationId(),
                    previous.followingObservationId()));
            events.notifyListeners(event.id().toString());
            previous.update(fetchedAt, null, null, null);
            previous.clearCandidate();
        } else {
            previous.update(fetchedAt, episode, previous.leadingObservationId(), previous.followingObservationId());
        }
    }

    private AlertEvent saveEvent(AlertEvent event) {
        boolean cleared = "CLEARED".equals(event.action());
        disruptions.recordBunchingTransition(event.alertId(), "SIMULATION".equals(event.classification())
                        ? "SIMULATION" : "OBSERVATION_ONLY", event.demoRunId(), cleared ? "CLEARED" : "ACTIVE",
                event.triggeredAt(), event.triggeredAt(), cleared ? event.triggeredAt() : null);
        return events.save(event);
    }
}
