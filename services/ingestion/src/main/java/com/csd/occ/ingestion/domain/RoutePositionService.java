package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RoutePositionService {
    private final RoutePositionRepository routes;
    private final RoutePositionSettings settings;
    private final Clock clock;

    public RoutePositionService(RoutePositionRepository routes, RoutePositionSettings settings, Clock clock) {
        this.routes = routes;
        this.settings = settings;
        this.clock = clock;
    }

    public Optional<RoutePositionSnapshot> positions(String serviceId) {
        Instant now = clock.instant();
        var pollInterval = settings.pollInterval();
        Instant from = now.minus(pollInterval.multipliedBy(settings.maxPollAgeCycles()));
        List<RoutePositionRoute> routeRows = routes.findRoutesWithObservations(serviceId, from, now);
        if (routeRows.isEmpty()) return Optional.empty();
        List<RoutePositionDirection> directions = routeRows.stream().map(route -> {
            RoutePositionPolicy.RoutePositionEstimate estimate = RoutePositionPolicy.estimateWithConfidence(
                        route.readings(), route.routeLengthM(), now, pollInterval, settings.maxPollAgeCycles(),
                        settings.clusterToleranceMeters(), settings.speedCapMetersPerSecond());
            return new RoutePositionDirection(route.operator(), route.directionId(), route.routeLengthM(),
                    estimate.buses(), estimate.suppressedBusCount());
        }).toList();
        return Optional.of(new RoutePositionSnapshot(serviceId, now, Math.max(1, pollInterval.toSeconds()), directions));
    }
}
