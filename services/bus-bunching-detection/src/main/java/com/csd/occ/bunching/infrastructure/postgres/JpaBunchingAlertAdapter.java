package com.csd.occ.bunching.infrastructure.postgres;

import com.csd.occ.bunching.domain.AlertEvent;
import com.csd.occ.bunching.domain.AlertEventRepository;
import com.csd.occ.bunching.domain.AlertState;
import com.csd.occ.bunching.domain.AlertStateId;
import com.csd.occ.bunching.domain.AlertStateRepository;
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
    private final AlertStateRepository states;
    private final AlertEventRepository events;
    private final BunchingEvidencePort evidence;
    private final Clock clock;
    private final BunchingAlertPolicy policy = new BunchingAlertPolicy();

    public JpaBunchingAlertAdapter(AlertStateRepository states, AlertEventRepository events,
                                   BunchingEvidencePort evidence, Clock clock) {
        this.states = states;
        this.events = events;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void trigger(BunchingEvaluation evaluation) {
        evaluation.groups().stream().filter(group -> group.group().directionId().isPresent())
                .sorted(Comparator.comparing((BunchingEvaluation.GroupEvaluation group) -> group.group().serviceId())
                        .thenComparing(group -> group.group().stopCode())
                        .thenComparingInt(group -> group.group().directionId().orElseThrow()))
                .forEach(result -> trigger(evaluation, result));
    }

    private void trigger(BunchingEvaluation evaluation, BunchingEvaluation.GroupEvaluation result) {
        if (result.reason() == BunchingEvaluation.Reason.STALE_SNAPSHOT
                || result.reason() == BunchingEvaluation.Reason.FUTURE_SNAPSHOT) return;
        int direction = result.group().directionId().orElseThrow();
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
}
