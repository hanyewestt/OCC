package com.csd.occ.planning.recommendation.infrastructure.ingestion;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationContextPort;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendation.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendation.domain.FollowingBusState;
import com.csd.occ.planning.recommendation.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendation.domain.SimulatedImpactEstimate;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationContext;
import com.csd.occ.planning.recommendation.infrastructure.fixture.ClasspathBunchingMitigationContextAdapter;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Builds recommendation inputs from the active simulation fleet, falling back to classpath fixtures. */
@Component
@Primary
public class HttpBunchingMitigationContextAdapter implements BunchingMitigationContextPort {
    private static final int MAXIMUM_HOLD_SECONDS = 120;
    private static final int MAXIMUM_DWELL_ADJUSTMENT_SECONDS = 75;

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
                var affectedBuses = context.fleet(scope.demoRunId()).items().stream()
                        .filter(bus -> event.serviceId().equals(bus.serviceId()) && event.directionId() == bus.directionId())
                        .sorted(Comparator.comparing(AdvisoryContextPort.Bus::busId))
                        .toList();
                if (affectedBuses.size() >= 2 && affectedBuses.size() <= 3) {
                    List<String> busIds = affectedBuses.stream().map(AdvisoryContextPort.Bus::busId).toList();
                    var mitigation = new ValidatedMitigationContext(
                            busIds.getLast(), busIds, event.monitoredStopCode(), FollowingBusState.READY_TO_DEPART,
                            MAXIMUM_HOLD_SECONDS, MAXIMUM_DWELL_ADJUSTMENT_SECONDS, CrowdSafetyStatus.PASSED,
                            MitigationValidationStatus.VALIDATED, event.evidenceIds());
                    int observedHeadway = event.observedHeadwaySeconds().getLast();
                    var impact = new SimulatedImpactEstimate(observedHeadway, event.expectedHeadwaySeconds(), 0, 0,
                            BigDecimal.ZERO, BigDecimal.ZERO);
                    return Optional.of(new BunchingMitigationInputs(mitigation, impact));
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back when simulation fleet resolution fails or is unavailable
        }
        return fallback != null ? fallback.findFor(event) : Optional.empty();
    }
}
