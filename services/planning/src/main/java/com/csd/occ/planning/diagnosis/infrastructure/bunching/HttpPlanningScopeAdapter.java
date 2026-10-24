package com.csd.occ.planning.diagnosis.infrastructure.bunching;

import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpPlanningScopeAdapter implements PlanningScopePort {
    private final RestClient client;

    public HttpPlanningScopeAdapter(@Qualifier("disruptionFactsRestClient") RestClient client) { this.client = client; }

    @Override
    public PlanningScope current() {
        try {
            PlanningScope scope = client.get().uri("/internal/v1/disruption/consumer-selection")
                    .retrieve().body(PlanningScope.class);
            if (scope == null) throw new IllegalStateException("Shared data selection is unavailable");
            return scope;
        } catch (RuntimeException unavailable) {
            throw new PlanningScopePort.UnavailableException(unavailable);
        }
    }
}
