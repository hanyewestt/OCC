package com.csd.occ.disruption.infrastructure.ingestion;

import com.csd.occ.disruption.hazard.domain.HazardPollPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpHazardPollAdapter implements HazardPollPort {
    private final RestClient ingestion;
    public HttpHazardPollAdapter(RestClient ingestionRestClient) { ingestion = ingestionRestClient; }
    public Page page(String kind, Instant afterAt, UUID afterId) {
        Page result = ingestion.get().uri(uri -> {
            var builder = uri.path("/internal/v1/hazard-polls").queryParam("kind", kind);
            if (afterAt != null) builder.queryParam("afterCompletedAt", afterAt).queryParam("afterPollRunId", afterId);
            return builder.build();
        }).retrieve().body(Page.class);
        if (result == null || result.polls() == null) throw new IllegalStateException("Hazard feed unavailable");
        return result;
    }
}
