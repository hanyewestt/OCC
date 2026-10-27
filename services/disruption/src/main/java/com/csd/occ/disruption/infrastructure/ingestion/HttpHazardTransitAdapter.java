package com.csd.occ.disruption.infrastructure.ingestion;

import com.csd.occ.disruption.hazard.domain.HazardTransitPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class HttpHazardTransitAdapter implements HazardTransitPort {
    private final RestClient ingestion;
    private final com.csd.occ.disruption.infrastructure.config.HazardProperties settings;
    public HttpHazardTransitAdapter(RestClient ingestionRestClient, com.csd.occ.disruption.infrastructure.config.HazardProperties settings) {
        ingestion = ingestionRestClient; this.settings = settings;
    }
    public double associationRadiusKm() { return settings.associationRadiusKm(); }
    public Fleet fleet(UUID runId, int page) {
        return java.util.Objects.requireNonNull(ingestion.get().uri(uri -> uri.path("/internal/v1/ingestion/demo-fleet")
                .queryParam("demoRunId", runId).queryParam("page", page).queryParam("size", 100).build())
                .retrieve().body(Fleet.class), "Fleet response required");
    }
    public Route route(String serviceId) {
        return java.util.Objects.requireNonNull(ingestion.get().uri("/internal/v1/ingestion/bus-routes/{serviceId}", serviceId)
                .retrieve().body(Route.class), "Route response required");
    }
    public Optional<Station> station(String code) {
        try { return Optional.ofNullable(ingestion.get().uri("/internal/v1/ingestion/stations/{code}", code).retrieve().body(Station.class)); }
        catch (HttpClientErrorException.NotFound unresolved) { return Optional.empty(); }
    }
}
