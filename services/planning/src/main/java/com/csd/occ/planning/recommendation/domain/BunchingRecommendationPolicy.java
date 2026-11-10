package com.csd.occ.planning.recommendation.domain;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BunchingRecommendationPolicy {

    public static final int SIMULATION_MAXIMUM_HOLD_SECONDS = 120;
    public static final int SIMULATION_MAXIMUM_DWELL_SECONDS = 75;
    private static final int ABSOLUTE_ADJUSTMENT_LIMIT_SECONDS = 120;

    public Optional<ValidatedMitigationRecommendation> evaluate(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis,
            ValidatedMitigationContext context,
            SimulatedImpactEstimate impact) {
        return evaluate(recommendationId, classification, event, diagnosis, context, impact, List.of());
    }

    public Optional<ValidatedMitigationRecommendation> evaluate(UUID recommendationId, SourceClassification classification,
            ConfirmedBunchingEvent event, BunchingRootCauseDiagnosis diagnosis, ValidatedMitigationContext context,
            SimulatedImpactEstimate impact, List<SuggestedBusOutcome> suggestions) {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(classification, "classification is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(diagnosis, "diagnosis is required");
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(impact, "impact is required");

        if (classification != SourceClassification.SIMULATION
                || event.status() != BunchingEventStatus.CONFIRMED
                || context.validationStatus() != MitigationValidationStatus.VALIDATED
                || context.crowdSafetyStatus() != CrowdSafetyStatus.PASSED) {
            return Optional.empty();
        }

        MitigationType mitigationType = mitigationTypeFor(context.followingBusState());
        int validatedLimitSeconds = validatedLimitFor(mitigationType, context);
        int latestObservedHeadwaySeconds = context.targetHeadwaySeconds(event);
        int headwayDeficitSeconds = event.expectedHeadwaySeconds() - latestObservedHeadwaySeconds;
        int adjustmentSeconds = Math.min(
                headwayDeficitSeconds,
                Math.min(validatedLimitSeconds, ABSOLUTE_ADJUSTMENT_LIMIT_SECONDS));

        if (adjustmentSeconds < 1) {
            return Optional.empty();
        }

        return Optional.of(new ValidatedMitigationRecommendation(
                recommendationId,
                IncidentType.BUS_BUNCHING,
                RecommendationStatus.RECOMMENDED,
                mitigationType,
                context.followingBusReference(),
                context.approvedControlStopCode(),
                adjustmentSeconds,
                explanationFor(mitigationType, adjustmentSeconds, diagnosis),
                impact,
                combinedEvidence(event, diagnosis, context), suggestions));
    }

    public List<SuggestedBusOutcome> suggestOutcomes(AdvisoryContextPort.CurrentBunchingGroup group,
            List<AdvisoryContextPort.Bus> fleet, AdvisoryContextPort.Route route, int expectedHeadway, Instant now) {
        var buses = group.affectedBusReferences();
        var outcomes = new ArrayList<SuggestedBusOutcome>(buses.size());
        outcomes.add(new SuggestedBusOutcome(buses.getFirst(), null, 0, "No preceding gap in the reviewed group."));
        for (int index = 1; index < buses.size(); index++) {
            var bus = buses.get(index);
            if (!group.hasReliableGap(index - 1)
                    || (index < buses.size() - 1 && !group.hasReliableGap(index))) {
                outcomes.add(new SuggestedBusOutcome(bus, null, 0, "An adjacent gap needs manual review."));
                continue;
            }
            long gap = group.adjacentGapSeconds().get(index - 1);
            outcomes.add(suggestOutcome(bus, expectedHeadway - gap, group, fleet, route, now));
        }
        // Resolve the following bus first so a removed delay cannot leave an earlier suggestion unsafe.
        for (int index = outcomes.size() - 2; index > 0; index--) {
            var outcome = outcomes.get(index);
            if (outcome.adjustmentSeconds() > 0 && !preservesGap(group.adjacentGapSeconds().get(index),
                    outcome.adjustmentSeconds(), outcomes.get(index + 1).adjustmentSeconds(), expectedHeadway)) {
                outcomes.set(index, new SuggestedBusOutcome(outcome.busReference(), null, 0,
                        "The adjustment would make the following gap too short."));
            }
        }
        return List.copyOf(outcomes);
    }

    public static boolean preservesGap(long originalGap, int precedingDelay, int followingDelay, int expectedHeadway) {
        long projectedGap = originalGap + followingDelay - precedingDelay;
        return projectedGap >= Math.min(originalGap, expectedHeadway);
    }

    private SuggestedBusOutcome suggestOutcome(String busId, long deficit, AdvisoryContextPort.CurrentBunchingGroup group,
            List<AdvisoryContextPort.Bus> fleet, AdvisoryContextPort.Route route, Instant now) {
        if (deficit <= 0)
            return new SuggestedBusOutcome(busId, null, 0, "The preceding gap already meets the expected headway.");
        var matches = fleet.stream().filter(bus -> busId.equals(bus.busId())
                && group.serviceId().equals(bus.serviceId()) && group.directionId() == bus.directionId()).toList();
        if (matches.size() != 1)
            return new SuggestedBusOutcome(busId, null, 0, "Current bus identity is unavailable or ambiguous.");
        var bus = matches.getFirst();
        if (bus.captainUserId() == null || !"ASSIGNED".equals(bus.assignmentStatus()))
            return new SuggestedBusOutcome(busId, null, 0, "No captain is assigned to this bus.");
        var progress = bus.progress();
        if (progress == null || !group.fetchedAt().equals(progress.observedAt()))
            return new SuggestedBusOutcome(busId, null, 0, "Bus progress for the reviewed snapshot is unavailable.");
        var stop = route == null || !group.serviceId().equals(route.serviceId()) ? Optional.<AdvisoryContextPort.Stop>empty()
                : route.upcomingStop(group.directionId(), group.stopCode(), progress.stopSequence());
        if (stop.isEmpty())
            return new SuggestedBusOutcome(busId, null, 0, "The upcoming control stop is unavailable or ambiguous.");
        if (progress.isBoardingAt(stop.orElseThrow(), group, now))
            return new SuggestedBusOutcome(busId, MitigationType.DWELL_ADJUSTMENT,
                    (int) Math.min(deficit, SIMULATION_MAXIMUM_DWELL_SECONDS), null);
        if (progress.isApproachingAt(now))
            return new SuggestedBusOutcome(busId, MitigationType.HOLD, (int) Math.min(deficit, SIMULATION_MAXIMUM_HOLD_SECONDS), null);
        return new SuggestedBusOutcome(busId, null, 0, "The bus has no fresh approaching or boarding evidence.");
    }

    private RecommendationExplanation explanationFor(
            MitigationType mitigationType,
            int adjustmentSeconds,
            BunchingRootCauseDiagnosis diagnosis) {
        String rationale = switch (mitigationType) {
            case HOLD -> "Hold the following bus at the approved control stop to widen the short headway.";
            case DWELL_ADJUSTMENT ->
                    "Extend dwell for the following bus at the approved control stop to widen the short headway.";
            case STANDBY_DISPATCH, SKIP_STOP -> throw new IllegalStateException("This action is not generated by the recommendation policy");
        };
        String tradeOff = switch (mitigationType) {
            case HOLD -> "Passengers on the held bus may experience up to "
                    + adjustmentSeconds + " seconds of additional delay.";
            case DWELL_ADJUSTMENT -> "Passengers on the adjusted bus may experience up to "
                    + adjustmentSeconds + " seconds of additional dwell time.";
            case STANDBY_DISPATCH, SKIP_STOP -> throw new IllegalStateException("This action is not generated by the recommendation policy");
        };
        return new RecommendationExplanation(
                rationale,
                diagnosis.likelyCause(),
                diagnosis.confidence(),
                List.of(tradeOff),
                diagnosis.limitations());
    }

    private MitigationType mitigationTypeFor(FollowingBusState followingBusState) {
        return switch (followingBusState) {
            case READY_TO_DEPART -> MitigationType.HOLD;
            case BOARDING_AT_CONTROL_STOP -> MitigationType.DWELL_ADJUSTMENT;
        };
    }

    private int validatedLimitFor(MitigationType mitigationType, ValidatedMitigationContext context) {
        return switch (mitigationType) {
            case HOLD -> context.maximumHoldSeconds();
            case DWELL_ADJUSTMENT -> context.maximumDwellAdjustmentSeconds();
            case STANDBY_DISPATCH, SKIP_STOP -> 0;
        };
    }

    private List<String> combinedEvidence(
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis,
            ValidatedMitigationContext context) {
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        evidenceIds.addAll(event.evidenceIds());
        evidenceIds.addAll(diagnosis.evidenceIds());
        evidenceIds.addAll(context.evidenceIds());
        return List.copyOf(new ArrayList<>(evidenceIds));
    }
}
