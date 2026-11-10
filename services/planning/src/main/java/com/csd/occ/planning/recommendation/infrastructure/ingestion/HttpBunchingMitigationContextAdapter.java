package com.csd.occ.planning.recommendation.infrastructure.ingestion;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationContextPort;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendation.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendation.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendation.domain.FollowingBusState;
import com.csd.occ.planning.recommendation.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendation.domain.SimulatedImpactEstimate;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationContext;
import com.csd.occ.planning.recommendation.infrastructure.fixture.ClasspathBunchingMitigationContextAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Builds recommendation inputs from the active simulation fleet, falling back to classpath fixtures. */
@Component
@Primary
public class HttpBunchingMitigationContextAdapter implements BunchingMitigationContextPort {
    private final PlanningScopePort scopes;
    private final AdvisoryContextPort context;
    private final ClasspathBunchingMitigationContextAdapter fallback;

    public HttpBunchingMitigationContextAdapter(PlanningScopePort scopes, AdvisoryContextPort context) {
        this(scopes, context, null);
    }

    @Autowired
    public HttpBunchingMitigationContextAdapter(PlanningScopePort scopes, AdvisoryContextPort context,
                                                ClasspathBunchingMitigationContextAdapter fallback) {
        this.scopes = scopes;
        this.context = context;
        this.fallback = fallback;
    }

    @Override
    public Optional<BunchingMitigationInputs> findFor(ConfirmedBunchingEvent event) {
        try {
            var scope = scopes.current();
            if (scope != null && scope.demoRunId() != null) {
                var group = context.bunchingGroup(scope.demoRunId(), event.bunchingEventId());
                List<String> busIds = group.affectedBusReferences();
                if (scope.demoRunId().equals(group.demoRunId()) && event.bunchingEventId().equals(group.bunchingEventId())
                        && event.serviceId().equals(group.serviceId()) && event.directionId() == group.directionId()
                        && event.monitoredStopCode().equals(group.stopCode()) && busIds != null
                        && busIds.size() >= 2 && busIds.size() <= 100 && group.followingBusReference() != null
                        && busIds.contains(group.followingBusReference())) {
                    var mitigation = new ValidatedMitigationContext(
                            group.followingBusReference(), busIds, event.monitoredStopCode(), FollowingBusState.READY_TO_DEPART,
                            BunchingRecommendationPolicy.SIMULATION_MAXIMUM_HOLD_SECONDS,
                            BunchingRecommendationPolicy.SIMULATION_MAXIMUM_DWELL_SECONDS, CrowdSafetyStatus.PASSED,
                            MitigationValidationStatus.VALIDATED, event.evidenceIds());
                    int observedHeadway = event.observedHeadwaySeconds().getLast();
                    var impact = new SimulatedImpactEstimate(observedHeadway, event.expectedHeadwaySeconds(), 0, 0,
                            BigDecimal.ZERO, BigDecimal.ZERO);
                    return Optional.of(new BunchingMitigationInputs(mitigation, impact));
                }
                return Optional.empty();
            }
        } catch (RuntimeException ignored) {
            // An unresolved incident must not be replaced with unrelated fixture vehicles.
            return Optional.empty();
        }
        return fallback != null ? fallback.findFor(event) : Optional.empty();
    }
}
