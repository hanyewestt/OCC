package com.csd.occ.planning.recommendationengine.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BunchingRecommendationPolicy {

    private static final int ABSOLUTE_ADJUSTMENT_LIMIT_SECONDS = 120;

    public Optional<ValidatedMitigationRecommendation> evaluate(
            UUID recommendationId,
            SourceClassification classification,
            ConfirmedBunchingEvent event,
            BunchingRootCauseDiagnosis diagnosis,
            ValidatedMitigationContext context,
            SimulatedImpactEstimate impact) {
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
        int latestObservedHeadwaySeconds = event.observedHeadwaySeconds()
                .getLast();
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
                combinedEvidence(event, diagnosis, context)));
    }

    private RecommendationExplanation explanationFor(
            MitigationType mitigationType,
            int adjustmentSeconds,
            BunchingRootCauseDiagnosis diagnosis) {
        String rationale = switch (mitigationType) {
            case HOLD -> "Hold the following bus at the approved control stop to widen the short headway.";
            case DWELL_ADJUSTMENT ->
                    "Extend dwell for the following bus at the approved control stop to widen the short headway.";
            case STANDBY_DISPATCH -> throw new IllegalStateException("Standby dispatch is not supported in Sprint 1");
        };
        String tradeOff = switch (mitigationType) {
            case HOLD -> "Passengers on the held bus may experience up to "
                    + adjustmentSeconds + " seconds of additional delay.";
            case DWELL_ADJUSTMENT -> "Passengers on the adjusted bus may experience up to "
                    + adjustmentSeconds + " seconds of additional dwell time.";
            case STANDBY_DISPATCH -> throw new IllegalStateException("Standby dispatch is not supported in Sprint 1");
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
            case STANDBY_DISPATCH -> 0;
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
