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
    private final BunchingObservationRepository observations;
    private final BunchingEvidencePort evidence;
    private final Clock clock;
    private final BunchingAlertPort alerts;

    public BunchingRecordingService(BunchingPolicy policy, BunchingObservationRepository observations,
                                    BunchingEvidencePort evidence, Clock clock, BunchingAlertPort alerts) {
        this.policy = policy;
        this.observations = observations;
        this.evidence = evidence;
        this.clock = clock;
        this.alerts = alerts;
    }

    @Transactional
    public RecordedBunching record(ArrivalSnapshot snapshot, Duration threshold, Duration staleAfter) {
        var existing = observations.findByRawObservationId(snapshot.rawObservationId());
        if (existing.isPresent()) {
            return result(existing.get(), RecordedBunching.Outcome.ALREADY_RECORDED);
        }
        var evaluation = policy.evaluate(snapshot, clock.instant(), threshold, staleAfter);
        int bunchedCount = (int) evaluation.groups().stream()
                .filter(group -> group.state() == BunchingEvaluation.State.BUNCHING).count();
        int inserted = observations.insertIfAbsent(snapshot.rawObservationId(), snapshot.pollRunId(),
                snapshot.referenceVersionId().map(Object::toString).orElse(""), snapshot.fetchedAt(), evaluation.evaluatedAt(),
                seconds(threshold), seconds(staleAfter), RULE_VERSION, snapshot.classification(),
                feedScope(snapshot), snapshot.demoRunId(), bunchedCount, evidence.encode(evaluation));
        // A concurrent winner has committed before ON CONFLICT returns under READ COMMITTED.
        if (inserted == 1) alerts.trigger(evaluation);
        var stored = observations.findByRawObservationId(snapshot.rawObservationId()).orElseThrow();
        return result(stored, inserted == 1 ? RecordedBunching.Outcome.RECORDED
                : RecordedBunching.Outcome.ALREADY_RECORDED);
    }

    private RecordedBunching result(BunchingObservation stored, RecordedBunching.Outcome outcome) {
        return new RecordedBunching(stored.rawObservationId(), stored.evaluatedAt(),
                stored.thresholdSeconds(), stored.bunchedGroupCount(), outcome);
    }

    private BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private String feedScope(ArrivalSnapshot snapshot) {
        return snapshot.demoRunId() == null ? "LIVE" : "DEMO:" + snapshot.demoRunId();
    }
}
