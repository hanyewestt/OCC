package com.csd.occ.planning.recommendation.domain;

import com.csd.occ.planning.advisory.domain.AdvisoryConflictException;
import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.diagnosis.domain.PlanningScopeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BunchingRecommendationRefreshService {
    private final RecommendationRecordPort records;
    private final RecommendationDecisionPort decisions;
    private final PlanningScopeService scopes;
    private final AdvisoryContextPort context;
    private final Clock clock;

    public BunchingRecommendationRefreshService(RecommendationRecordPort records, RecommendationDecisionPort decisions,
            PlanningScopeService scopes, AdvisoryContextPort context, Clock clock) {
        this.records = records;
        this.decisions = decisions;
        this.scopes = scopes;
        this.context = context;
        this.clock = clock;
    }

    @Transactional
    public RecommendationRecord refresh(UUID id) {
        var previous = records.findByIdempotencyKeyForUpdate(id)
                .orElseThrow(() -> new RecommendationDecisionService.RecommendationNotFoundException(id));
        var run = scopes.current().demoRunId();
        if (run == null || previous.classification() != SourceClassification.SIMULATION
                || previous.recommendation().status() != RecommendationStatus.RECOMMENDED
                || !scopes.includesDiagnosis(previous.diagnosis().diagnosisId())) {
            throw new RecommendationDecisionService.RecommendationNotActionableException(id);
        }
        var successor = records.findRevision(id);
        if (successor.isPresent()) {
            var successorId = successor.orElseThrow().recommendationId();
            return records.findByIdempotencyKey(successorId).orElseThrow();
        }
        if (decisions.findByRecommendationId(id).isPresent()) {
            throw new AdvisoryConflictException("This recommendation already has a decision");
        }

        var event = previous.bunchingEvent();
        AdvisoryContextPort.CurrentBunchingGroup group;
        try {
            group = context.currentBunchingGroup(run, event.bunchingEventId());
        } catch (RuntimeException unavailable) {
            throw new AdvisoryConflictException("Current bunching evidence is unavailable");
        }
        var now = clock.instant();
        if (group == null || !run.equals(group.demoRunId()) || !event.bunchingEventId().equals(group.bunchingEventId())
                || !event.serviceId().equals(group.serviceId()) || event.directionId() != group.directionId()
                || !event.monitoredStopCode().equals(group.stopCode()) || !group.isFreshAt(now)) {
            throw new AdvisoryConflictException("Current bunching evidence is unavailable or expired");
        }
        var buses = group.affectedBusReferences();
        var gaps = group.adjacentGapSeconds();
        if (buses == null || buses.size() < 2 || buses.size() > 100
                || buses.stream().anyMatch(bus -> bus == null || bus.isBlank())
                || buses.stream().distinct().count() != buses.size()
                || gaps == null || gaps.size() != buses.size() - 1
                || gaps.stream().anyMatch(gap -> gap == null || gap < 0 || gap > Integer.MAX_VALUE)) {
            throw new AdvisoryConflictException("Current bus identities or prediction gaps are unavailable");
        }
        if (group.rawObservationId().equals(previous.revisionRawObservationId())) {
            return previous;
        }

        // The recommendation proposes a hold before arrival; issuance validates any controller dwell override.
        var mitigation = new ValidatedMitigationContext(buses.getLast(), buses, group.stopCode(),
                FollowingBusState.READY_TO_DEPART, BunchingRecommendationPolicy.SIMULATION_MAXIMUM_HOLD_SECONDS,
                BunchingRecommendationPolicy.SIMULATION_MAXIMUM_DWELL_SECONDS, CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED, List.of(group.rawObservationId().toString()), gaps);
        int gap = mitigation.targetHeadwaySeconds(event);
        var impact = new SimulatedImpactEstimate(gap,
                gap + Math.max(0, Math.min(event.expectedHeadwaySeconds() - gap, mitigation.maximumHoldSeconds())), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        UUID revisionId = UUID.randomUUID();
        var recommendation = new BunchingRecommendationPolicy().evaluate(revisionId, previous.classification(),
                event, previous.diagnosis(), mitigation, impact)
                .orElseThrow(() -> new AdvisoryConflictException("Current predictions do not support a hold recommendation"));
        var replacement = new RecommendationRecord(revisionId, previous.requestHash(), previous.requestId(),
                previous.classification(), event, previous.diagnosis(), mitigation, recommendation, now, group.rawObservationId());
        if (!records.supersede(id, replacement, group.rawObservationId())) {
            throw new AdvisoryConflictException("The recommendation changed while it was being refreshed");
        }
        return replacement;
    }
}
