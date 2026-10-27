package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.RoutePositionSettings;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredRoutePositionSettings implements RoutePositionSettings {
    private final RoutePositionProperties positions;
    private final IngestionScheduleProperties schedule;
    private final Set<String> identityLinkRoutes;
    private final Set<String> coordinatedRefreshBoundaryRoutes;
    private final Set<String> circularPhaseLinkRoutes;

    public ConfiguredRoutePositionSettings(RoutePositionProperties positions, IngestionScheduleProperties schedule) {
        this.positions = positions;
        this.schedule = schedule;
        this.identityLinkRoutes = Set.copyOf(positions.identityLinkRoutes());
        this.coordinatedRefreshBoundaryRoutes = Set.copyOf(positions.coordinatedRefreshBoundaryRoutes());
        this.circularPhaseLinkRoutes = Set.copyOf(positions.circularPhaseLinkRoutes());
    }

    @Override
    public Duration pollInterval() { return schedule.busArrivalPollInterval(); }

    @Override
    public int maxPollAgeCycles() { return positions.maxPollAgeCycles(); }

    @Override
    public double clusterToleranceMeters() { return positions.clusterToleranceMeters(); }

    @Override
    public double speedCapMetersPerSecond() { return positions.speedCapMetersPerSecond(); }

    @Override
    public double uncertaintyMeters() { return positions.uncertaintyMeters(); }

    @Override
    public double circularProjectionSpeedMetersPerSecond() {
        return positions.circularProjectionSpeedMetersPerSecond();
    }

    @Override
    public int circularProjectionMinimumStopCount() { return positions.circularProjectionMinimumStopCount(); }

    @Override
    public double identityLinkDistanceMeters() { return positions.identityLinkDistanceMeters(); }

    @Override
    public double identityLinkProjectionSpeedMetersPerSecond() {
        return positions.identityLinkProjectionSpeedMetersPerSecond();
    }

    @Override
    public Duration identityEtaTolerance() { return positions.identityEtaTolerance(); }

    @Override
    public boolean isIdentityLinkEnabled(String serviceId, int directionId) {
        return identityLinkRoutes.contains(routeDirection(serviceId, directionId));
    }

    @Override
    public boolean isCoordinatedRefreshBoundaryEnabled(String serviceId, int directionId) {
        return coordinatedRefreshBoundaryRoutes.contains(routeDirection(serviceId, directionId));
    }

    @Override
    public double refreshBoundaryDistanceMeters() { return positions.refreshBoundaryDistanceMeters(); }

    @Override
    public Duration refreshBoundaryWindow() { return positions.refreshBoundaryWindow(); }

    @Override
    public Duration refreshBoundaryAllowedOverlap() { return positions.refreshBoundaryAllowedOverlap(); }

    @Override
    public Duration refreshBoundaryProfileResidual() { return positions.refreshBoundaryProfileResidual(); }

    @Override
    public double refreshBoundaryProfileMinimumSecondsPerMeter() {
        return positions.refreshBoundaryProfileMinimumSecondsPerMeter();
    }

    @Override
    public double refreshBoundaryProfileMaximumSecondsPerMeter() {
        return positions.refreshBoundaryProfileMaximumSecondsPerMeter();
    }

    @Override
    public double circularPhaseLinkDistanceMeters() { return positions.circularPhaseLinkDistanceMeters(); }

    @Override
    public double circularPhaseMaximumGapMeters() { return positions.circularPhaseMaximumGapMeters(); }

    @Override
    public Duration circularPhaseMaximumEtaError() { return positions.circularPhaseMaximumEtaError(); }

    @Override
    public boolean isCircularPhaseLinkEnabled(String serviceId, int directionId) {
        return circularPhaseLinkRoutes.contains(routeDirection(serviceId, directionId));
    }

    private static String routeDirection(String serviceId, int directionId) {
        return serviceId.strip().toUpperCase(Locale.ROOT) + ":" + directionId;
    }
}
