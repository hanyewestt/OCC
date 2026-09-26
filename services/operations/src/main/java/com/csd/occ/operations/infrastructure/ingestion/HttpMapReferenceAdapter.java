package com.csd.occ.operations.infrastructure.ingestion;

import com.csd.occ.operations.domain.MapReferencePort;
import com.csd.occ.operations.domain.RouteReferenceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpMapReferenceAdapter implements MapReferencePort {
    private final RestClient ingestion;
    public HttpMapReferenceAdapter(RestClient ingestionTopologyClient) { ingestion = ingestionTopologyClient; }
    @Override public ServicePage services(int page, int size) {
        return read("/api/v1/bus-routes?page={page}&size={size}", ServicePage.class, page, size);
    }
    @Override public Route route(String serviceId) {
        return read("/api/v1/bus-routes/{serviceId}", Route.class, serviceId);
    }
    @Override public ArrivalPage arrivals(String serviceId, String stopCode, int page, int size) {
        return read("/api/v1/arrivals?serviceId={serviceId}&stopCode={stopCode}&page={page}&size={size}",
                ArrivalPage.class, serviceId, stopCode, page, size);
    }
    private <T> T read(String path, Class<T> type, Object... variables) {
        try {
            T result = ingestion.get().uri(path, variables).retrieve().body(type);
            if (result == null) throw new RestClientException("Empty map reference response");
            return result;
        } catch (RestClientException error) { throw new RouteReferenceUnavailableException(error); }
    }
}
