package com.csd.occ.planning.advisory.infrastructure.http;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.advisory.infrastructure.config.AdvisoryProperties;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpAdvisoryContextAdapter implements AdvisoryContextPort {
    private final RestClient disruption;
    private final RestClient ingestion;
    private final AdvisoryProperties settings;
    public HttpAdvisoryContextAdapter(@Qualifier("disruptionFactsRestClient") RestClient disruption,
            @Qualifier("ingestionContextRestClient") RestClient ingestion, AdvisoryProperties settings) {
        this.disruption = disruption; this.ingestion = ingestion; this.settings = settings;
    }
    public Hazard hazard(UUID id) {
        return Objects.requireNonNull(disruption.get().uri("/internal/v1/disruption/hazards/{id}", id).retrieve().body(Hazard.class));
    }
    public Fleet fleet(UUID id) {
        var result = Objects.requireNonNull(ingestion.get().uri(uri -> uri.path("/internal/v1/ingestion/demo-fleet")
                .queryParam("demoRunId", id).queryParam("size", 100).build()).retrieve().body(Fleet.class));
        if (result.totalElements() > result.items().size()) throw new IllegalStateException("Fleet snapshot incomplete");
        return result;
    }
    public Limits limits() {
        return new Limits(settings.maxSpeedReductionKmh(), settings.maxDurationSeconds(), settings.maxSubsequentStops(),
                settings.acknowledgementWindow(), settings.retention());
    }
    public Route route(String serviceId) {
        return Objects.requireNonNull(ingestion.get().uri("/internal/v1/ingestion/bus-routes/{id}", serviceId).retrieve().body(Route.class));
    }
}
