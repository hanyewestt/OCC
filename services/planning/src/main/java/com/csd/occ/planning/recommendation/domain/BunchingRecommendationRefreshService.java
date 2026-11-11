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

        var replacement = reviewCurrentGroup(previous, run, UUID.randomUUID());
        if (replacement == previous) return previous;
        if (!records.supersede(id, replacement, replacement.revisionRawObservationId())) {
            throw new AdvisoryConflictException("The recommendation changed while it was being refreshed");
        }
        return replacement;
    }

    RecommendationRecord initialReview(RecommendationRecord original) {
        if (original.classification() != SourceClassification.SIMULATION
                || original.mitigationContext().affectedBusReferences().size() < 2) return original;
        try {
            var scope = scopes.current();
            return scope == null || scope.demoRunId() == null ? original
                    : reviewCurrentGroup(original, scope.demoRunId(), original.idempotencyKey());
        } catch (AdvisoryConflictException | com.csd.occ.planning.diagnosis.domain.PlanningScopePort.UnavailableException unavailable) {
            return original;
        }
    }

    private RecommendationRecord reviewCurrentGroup(RecommendationRecord previous, UUID run, UUID revisionId) {
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
        List<AdvisoryContextPort.Bus> fleet = List.of();
        AdvisoryContextPort.Route route = null;
        try {
            var currentFleet = context.fleet(run);
            if (currentFleet != null && run.equals(currentFleet.demoRunId())
                    && List.of("RUNNING", "REPLAY_COMPLETE").contains(currentFleet.status())) {
                fleet = currentFleet.items();
                route = context.route(group.serviceId());
            }
        } catch (RuntimeException unavailable) {
            // The policy records unavailable bus or stop evidence as no-instruction outcomes.
        }
        now = clock.instant();
        if (!group.isFreshAt(now)) throw new AdvisoryConflictException("Current bunching evidence expired during review");
        var policy = new BunchingRecommendationPolicy();
        var suggestions = policy.suggestOutcomes(group, fleet, route, event.expectedHeadwaySeconds(), now);
        if (group.rawObservationId().equals(previous.revisionRawObservationId())
                && suggestions.equals(previous.recommendation().suggestedOutcomes())) {
            return previous;
        }

        // Preserve the single-target summary used by existing clients.
        var mitigation = new ValidatedMitigationContext(buses.getLast(), buses, group.stopCode(),
                FollowingBusState.READY_TO_DEPART, BunchingRecommendationPolicy.SIMULATION_MAXIMUM_HOLD_SECONDS,
                BunchingRecommendationPolicy.SIMULATION_MAXIMUM_DWELL_SECONDS, CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED, List.of(group.rawObservationId().toString()), gaps);
        int gap = mitigation.targetHeadwaySeconds(event);
        var impact = new SimulatedImpactEstimate(gap,
                gap + Math.max(0, Math.min(event.expectedHeadwaySeconds() - gap, mitigation.maximumHoldSeconds())), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        var recommendation = policy.evaluate(revisionId, previous.classification(),
                event, previous.diagnosis(), mitigation, impact, suggestions)
                .orElseThrow(() -> new AdvisoryConflictException("Current predictions do not support a hold recommendation"));
        return new RecommendationRecord(revisionId, previous.requestHash(), previous.requestId(),
                previous.classification(), event, previous.diagnosis(), mitigation, recommendation, now, group.rawObservationId());
    }
}
