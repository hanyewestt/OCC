package com.csd.occ.bunching.infrastructure.postgres;

import com.csd.occ.bunching.domain.AlertEvent;
import com.csd.occ.bunching.domain.AlertEventRepository;
import com.csd.occ.bunching.domain.AlertState;
import com.csd.occ.bunching.domain.AlertStateId;
import com.csd.occ.bunching.domain.AlertStateRepository;
import com.csd.occ.bunching.domain.ConsumerSelectionService;
import com.csd.occ.bunching.domain.DemoAlertState;
import com.csd.occ.bunching.domain.DemoAlertStateId;
import com.csd.occ.bunching.domain.DemoAlertStateRepository;
import com.csd.occ.bunching.domain.BunchingAlertPolicy;
import com.csd.occ.bunching.domain.BunchingAlertPort;
import com.csd.occ.bunching.domain.BunchingEvaluation;
import com.csd.occ.bunching.domain.BunchingEvidencePort;
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
    private final AlertStateRepository states;
    private final AlertEventRepository events;
    private final BunchingEvidencePort evidence;
    private final Clock clock;
    private final ConsumerSelectionService selection;
    private final DemoAlertStateRepository demoStates;
    private final BunchingAlertPolicy policy = new BunchingAlertPolicy();

    public JpaBunchingAlertAdapter(AlertStateRepository states, AlertEventRepository events,
                                   BunchingEvidencePort evidence, Clock clock, ConsumerSelectionService selection,
                                   DemoAlertStateRepository demoStates) {
        this.states = states;
        this.events = events;
        this.evidence = evidence;
        this.clock = clock;
        this.selection = selection;
        this.demoStates = demoStates;
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
        AlertStateId id = new AlertStateId(result.group().serviceId(), result.group().stopCode(), direction);
        states.insertIfAbsent(id.serviceId(), id.stopCode(), id.directionId(), Instant.EPOCH);
        AlertState previous = states.findWithLockById(id).orElseThrow();
        Instant fetchedAt = evaluation.snapshot().fetchedAt();
        if (!fetchedAt.isAfter(previous.lastFetchedAt())) return;
        BunchingAlertPolicy.Transition transition = policy.transition(result.state(),
                Optional.ofNullable(previous.activeAlertId()));
        UUID episode = transition == BunchingAlertPolicy.Transition.OPENED ? UUID.randomUUID() : previous.activeAlertId();
        if (transition != BunchingAlertPolicy.Transition.NONE) {
            AlertEvent event = events.save(new AlertEvent(episode, transition.name(), id.serviceId(), id.stopCode(), direction,
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
        DemoAlertStateId id = new DemoAlertStateId(runId, result.group().serviceId(), result.group().stopCode(), direction);
        demoStates.insertIfAbsent(runId, id.serviceId(), id.stopCode(), id.directionId(), Instant.EPOCH);
        DemoAlertState previous = demoStates.findWithLockById(id).orElseThrow();
        Instant fetchedAt = evaluation.snapshot().fetchedAt();
        if (!fetchedAt.isAfter(previous.lastFetchedAt())) return;
        BunchingAlertPolicy.Transition transition = policy.transition(result.state(), Optional.ofNullable(previous.activeAlertId()));
        UUID episode = transition == BunchingAlertPolicy.Transition.OPENED ? UUID.randomUUID() : previous.activeAlertId();
        UUID leading = previous.leadingObservationId();
        UUID following = previous.followingObservationId();
        if (transition == BunchingAlertPolicy.Transition.OPENED) {
            var bunchedPair = result.headways().stream().filter(BunchingEvaluation.Headway::isBunched).findFirst();
            leading = bunchedPair.map(BunchingEvaluation.Headway::leadingObservationId).orElse(null);
            following = bunchedPair.map(BunchingEvaluation.Headway::followingObservationId).orElse(null);
        }
        if (transition != BunchingAlertPolicy.Transition.NONE) {
            AlertEvent event = events.save(new AlertEvent(episode, transition.name(), id.serviceId(), id.stopCode(), direction,
                    evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId(), fetchedAt, clock.instant(),
                    evidence.encodeAlert(evaluation, result), "SIMULATION", runId, leading, following));
            events.notifyListeners(event.id().toString());
        }
        if (transition == BunchingAlertPolicy.Transition.CLEARED)
            previous.update(fetchedAt, null, null, null);
        else
            previous.update(fetchedAt, episode, leading, following);
    }
}
