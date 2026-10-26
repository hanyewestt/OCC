package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
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
        RoutePositionPolicy.Parameters parameters = new RoutePositionPolicy.Parameters(
                pollInterval, settings.maxPollAgeCycles(), settings.clusterToleranceMeters(),
                settings.speedCapMetersPerSecond(), settings.uncertaintyMeters(),
                settings.circularProjectionSpeedMetersPerSecond(), settings.circularProjectionMinimumStopCount(),
                settings.identityLinkDistanceMeters(), settings.identityLinkProjectionSpeedMetersPerSecond(),
                settings.identityEtaTolerance(),
                settings.refreshBoundaryDistanceMeters(), settings.refreshBoundaryWindow(),
                settings.refreshBoundaryAllowedOverlap(), settings.refreshBoundaryProfileResidual(),
                settings.refreshBoundaryProfileMinimumSecondsPerMeter(),
                settings.refreshBoundaryProfileMaximumSecondsPerMeter(),
                settings.circularPhaseLinkDistanceMeters(), settings.circularPhaseMaximumGapMeters(),
                settings.circularPhaseMaximumEtaError());
        List<RoutePositionDirection> directions = routeRows.stream().map(route -> {
            RoutePositionPolicy.RoutePositionEstimate estimate = route.selectedPollCycleId() == null
                    ? new RoutePositionPolicy.RoutePositionEstimate(List.of(), 0)
                    : RoutePositionPolicy.estimateWithConfidence(
                            route.readings(), route.routeLengthM(), route.circular(), route.referenceLengthM(), now,
                            parameters, route.selectedPollCycleId(),
                            settings.isIdentityLinkEnabled(serviceId, route.directionId()),
                            settings.isCoordinatedRefreshBoundaryEnabled(serviceId, route.directionId()),
                            settings.isCircularPhaseLinkEnabled(serviceId, route.directionId()));
            return new RoutePositionDirection(route.operator(), route.directionId(), route.routeLengthM(),
                    estimate.buses(), estimate.suppressedBusCount());
        }).toList();
        Optional<RoutePositionReading> latestSourceReading = routeRows.stream()
                .flatMap(route -> route.readings().stream()
                        .filter(reading -> route.selectedPollCycleId() != null
                                && route.selectedPollCycleId().equals(reading.pollCycleId())))
                .max(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparing(reading -> reading.pollCycleId().toString()));
        return Optional.of(new RoutePositionSnapshot(serviceId, now,
                latestSourceReading.map(RoutePositionReading::pollCycleId),
                latestSourceReading.map(RoutePositionReading::fetchedAt),
                Math.max(1, pollInterval.toSeconds()),
                Math.max(1, pollInterval.multipliedBy(settings.maxPollAgeCycles()).toSeconds()), directions));
    }
}
