package com.csd.occ.bunching.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

@Service
public class BunchingRecordingService {
    public static final String RULE_VERSION = "absolute-headway-v2";

    private final BunchingPolicy policy;
    private final SourceProcessingStateRepository states;
    private final Clock clock;
    private final BunchingAlertPort alerts;

    public BunchingRecordingService(BunchingPolicy policy, SourceProcessingStateRepository states,
                                    Clock clock, BunchingAlertPort alerts) {
        this.policy = policy;
        this.states = states;
        this.clock = clock;
        this.alerts = alerts;
    }

    @Transactional
    public RecordedBunching record(ArrivalSnapshot snapshot, Duration threshold, Duration staleAfter) {
        var existing = states.findByRawObservationId(snapshot.rawObservationId());
        if (existing.isPresent()) {
            return result(existing.get(), RecordedBunching.Outcome.ALREADY_RECORDED);
        }
        var evaluation = policy.evaluate(snapshot, clock.instant(), threshold, staleAfter);
        int bunchedCount = (int) evaluation.groups().stream()
                .filter(group -> group.state() == BunchingEvaluation.State.BUNCHING).count();
        int inserted = states.insertProcessedIfAbsent(snapshot.rawObservationId(), snapshot.demoRunId(), evaluation.evaluatedAt(),
                seconds(threshold), bunchedCount);
        // A concurrent winner has committed before ON CONFLICT returns under READ COMMITTED.
        if (inserted == 1) alerts.trigger(evaluation);
        var stored = states.findByRawObservationId(snapshot.rawObservationId()).orElseThrow();
        return result(stored, inserted == 1 ? RecordedBunching.Outcome.RECORDED
                : RecordedBunching.Outcome.ALREADY_RECORDED);
    }

    private RecordedBunching result(SourceProcessingState stored, RecordedBunching.Outcome outcome) {
        return new RecordedBunching(stored.rawObservationId(), stored.evaluatedAt(),
                stored.thresholdSeconds(), stored.bunchedGroupCount(), outcome);
    }

    private BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }
}
