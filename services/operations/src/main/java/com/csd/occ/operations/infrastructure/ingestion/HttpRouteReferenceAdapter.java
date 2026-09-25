package com.csd.occ.operations.infrastructure.ingestion;

import com.csd.occ.operations.domain.RouteReferencePort;
import com.csd.occ.operations.domain.RouteReferenceUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpRouteReferenceAdapter implements RouteReferencePort {
    private final RestClient ingestion;

    public HttpRouteReferenceAdapter(RestClient ingestionTopologyClient) {
        this.ingestion = ingestionTopologyClient;
    }

    @Override
    public Optional<Route> route(String serviceId) {
        try {
            RouteResponse response = ingestion.get()
                    .uri(builder -> builder.pathSegment("api", "v1", "bus-routes", serviceId).build())
                    .retrieve().body(RouteResponse.class);
            if (response == null || response.operators() == null) {
                throw new RouteReferenceUnavailableException(new IllegalStateException("Empty route response"));
            }
            List<Direction> directions = response.operators().stream()
                    .filter(operator -> operator.directions() != null)
                    .flatMap(operator -> operator.directions().stream())
                    .map(direction -> new Direction(direction.directionId(), direction.stops().stream()
                            .map(stop -> new Stop(stop.stopCode(), stop.latitude(), stop.longitude())).toList()))
                    .toList();
            return Optional.of(new Route(response.serviceId(), directions));
        } catch (HttpClientErrorException.NotFound ignored) {
            return Optional.empty();
        } catch (RestClientException error) {
            throw new RouteReferenceUnavailableException(error);
        }
    }

    private record RouteResponse(String serviceId, List<OperatorResponse> operators) { }
    private record OperatorResponse(List<DirectionResponse> directions) { }
    private record DirectionResponse(int directionId, List<StopResponse> stops) { }
    private record StopResponse(String stopCode, BigDecimal latitude, BigDecimal longitude) { }
}
