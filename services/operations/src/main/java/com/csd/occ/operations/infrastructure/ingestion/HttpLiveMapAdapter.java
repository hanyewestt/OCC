package com.csd.occ.operations.infrastructure.ingestion;

import com.csd.occ.operations.domain.LiveMapPort;
import com.csd.occ.operations.domain.RouteReferenceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpLiveMapAdapter implements LiveMapPort {
    private final RestClient ingestion;
    public HttpLiveMapAdapter(RestClient ingestionTopologyClient) { this.ingestion = ingestionTopologyClient; }
    @Override
    public Page observations(String serviceId, int page, int size) {
        try {
            Page result = ingestion.get().uri(builder -> {
                builder.path("/api/v1/map/observed-buses").queryParam("page", page).queryParam("size", size);
                if (serviceId != null && !serviceId.isBlank()) builder.queryParam("serviceId", serviceId);
                return builder.build();
            }).retrieve().body(Page.class);
            if (result == null) throw new RestClientException("Empty observation response");
            return result;
        } catch (RestClientException error) { throw new RouteReferenceUnavailableException(error); }
    }
}
