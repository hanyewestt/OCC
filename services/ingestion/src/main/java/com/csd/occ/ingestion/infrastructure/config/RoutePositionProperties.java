package com.csd.occ.ingestion.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.route-positions")
public record RoutePositionProperties(double clusterToleranceMeters, int maxPollAgeCycles,
                                      double speedCapMetersPerSecond, double uncertaintyMeters,
                                      double circularProjectionSpeedMetersPerSecond,
                                      int circularProjectionMinimumStopCount,
                                      double identityLinkDistanceMeters,
                                      double identityLinkProjectionSpeedMetersPerSecond,
                                      Duration identityEtaTolerance,
                                      List<String> identityLinkRoutes,
                                      List<String> coordinatedRefreshBoundaryRoutes,
                                      double refreshBoundaryDistanceMeters, Duration refreshBoundaryWindow,
                                      Duration refreshBoundaryAllowedOverlap,
                                      Duration refreshBoundaryProfileResidual,
                                      double refreshBoundaryProfileMinimumSecondsPerMeter,
                                      double refreshBoundaryProfileMaximumSecondsPerMeter,
                                      double circularPhaseLinkDistanceMeters,
                                      double circularPhaseMaximumGapMeters,
                                      Duration circularPhaseMaximumEtaError,
                                      List<String> circularPhaseLinkRoutes) {
    public RoutePositionProperties {
        positiveDistance(clusterToleranceMeters, "cluster-tolerance-meters");
        if (maxPollAgeCycles < 1 || maxPollAgeCycles > 5)
            throw new IllegalArgumentException("route-position max-poll-age-cycles must be from 1 through 5");
        if (!Double.isFinite(speedCapMetersPerSecond) || speedCapMetersPerSecond <= 0
                || speedCapMetersPerSecond > 40)
            throw new IllegalArgumentException("route-position speed-cap-meters-per-second must be from 0 through 40");
        if (!Double.isFinite(uncertaintyMeters) || uncertaintyMeters <= 0 || uncertaintyMeters > 5_000)
            throw new IllegalArgumentException("route-position uncertainty-meters must be from 0 through 5000");
        if (!Double.isFinite(circularProjectionSpeedMetersPerSecond)
                || circularProjectionSpeedMetersPerSecond <= 0 || circularProjectionSpeedMetersPerSecond > 40)
            throw new IllegalArgumentException(
                    "route-position circular-projection-speed-meters-per-second must be from 0 through 40");
        if (circularProjectionMinimumStopCount < 2 || circularProjectionMinimumStopCount > 10)
            throw new IllegalArgumentException(
                    "route-position circular-projection-minimum-stop-count must be from 2 through 10");
        positiveDistance(identityLinkDistanceMeters, "identity-link-distance-meters");
        if (!Double.isFinite(identityLinkProjectionSpeedMetersPerSecond)
                || identityLinkProjectionSpeedMetersPerSecond <= 0
                || identityLinkProjectionSpeedMetersPerSecond > 40)
            throw new IllegalArgumentException(
                    "route-position identity-link-projection-speed-meters-per-second must be from 0 through 40");
        positiveDuration(identityEtaTolerance, "identity-eta-tolerance");
        identityLinkRoutes = normalizeRouteDirections(identityLinkRoutes, "identity-link-routes");
        coordinatedRefreshBoundaryRoutes = normalizeRouteDirections(
                coordinatedRefreshBoundaryRoutes, "coordinated-refresh-boundary-routes");
        positiveDistance(refreshBoundaryDistanceMeters, "refresh-boundary-distance-meters");
        positiveDuration(refreshBoundaryWindow, "refresh-boundary-window");
        if (refreshBoundaryAllowedOverlap == null || refreshBoundaryAllowedOverlap.isNegative()
                || refreshBoundaryAllowedOverlap.compareTo(refreshBoundaryWindow) > 0)
            throw new IllegalArgumentException(
                    "route-position refresh-boundary-allowed-overlap must be non-negative and no longer than refresh-boundary-window");
        positiveDuration(refreshBoundaryProfileResidual, "refresh-boundary-profile-residual");
        positiveRate(refreshBoundaryProfileMinimumSecondsPerMeter,
                "refresh-boundary-profile-minimum-seconds-per-meter");
        positiveRate(refreshBoundaryProfileMaximumSecondsPerMeter,
                "refresh-boundary-profile-maximum-seconds-per-meter");
        if (refreshBoundaryProfileMaximumSecondsPerMeter <= refreshBoundaryProfileMinimumSecondsPerMeter)
            throw new IllegalArgumentException(
                    "route-position refresh-boundary-profile-maximum-seconds-per-meter must exceed the minimum");
        positiveDistance(circularPhaseLinkDistanceMeters, "circular-phase-link-distance-meters");
        positiveDistance(circularPhaseMaximumGapMeters, "circular-phase-maximum-gap-meters");
        positiveDuration(circularPhaseMaximumEtaError, "circular-phase-maximum-eta-error");
        circularPhaseLinkRoutes = normalizeRouteDirections(circularPhaseLinkRoutes, "circular-phase-link-routes");
    }

    private static void positiveDistance(double value, String name) {
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("route-position " + name + " must be positive");
    }

    private static void positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException("route-position " + name + " must be positive");
    }

    private static void positiveRate(double value, String name) {
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("route-position " + name + " must be positive");
    }

    private static List<String> normalizeRouteDirections(List<String> configuredRoutes, String propertyName) {
        if (configuredRoutes == null)
            throw new IllegalArgumentException("route-position " + propertyName + " must be set");
        return configuredRoutes.stream()
                .map(route -> normalizeRouteDirection(route, propertyName))
                .distinct()
                .toList();
    }

    private static String normalizeRouteDirection(String configuredRoute, String propertyName) {
        if (configuredRoute == null)
            throw new IllegalArgumentException("route-position " + propertyName + " contains null");
        String normalized = configuredRoute.strip().toUpperCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1)
            throw invalidRouteDirection(configuredRoute, propertyName);
        String serviceId = normalized.substring(0, separator).strip();
        String rawDirection = normalized.substring(separator + 1).strip();
        try {
            int directionId = Integer.parseInt(rawDirection);
            if (directionId < 1) throw invalidRouteDirection(configuredRoute, propertyName);
            return serviceId + ":" + directionId;
        } catch (NumberFormatException exception) {
            throw invalidRouteDirection(configuredRoute, propertyName);
        }
    }

    private static IllegalArgumentException invalidRouteDirection(String configuredRoute, String propertyName) {
        return new IllegalArgumentException("route-position " + propertyName + " entry '"
                + configuredRoute + "' must have the form service:positive-direction");
    }
}
