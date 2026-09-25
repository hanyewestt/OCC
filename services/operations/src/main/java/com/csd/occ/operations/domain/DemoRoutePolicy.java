package com.csd.occ.operations.domain;

import java.math.BigDecimal;

public class DemoRoutePolicy {
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private final double maxStopDistanceMeters;

    public DemoRoutePolicy(double maxStopDistanceMeters) {
        if (maxStopDistanceMeters <= 0) throw new IllegalArgumentException("Maximum stop distance must be positive");
        this.maxStopDistanceMeters = maxStopDistanceMeters;
    }

    public boolean hasDirection(RouteReferencePort.Route route, int directionId) {
        return route.directions().stream().anyMatch(direction -> direction.directionId() == directionId);
    }

    public boolean isNearRouteStop(RouteReferencePort.Route route, int directionId,
            BigDecimal latitude, BigDecimal longitude) {
        return route.directions().stream()
                .filter(direction -> direction.directionId() == directionId)
                .flatMap(direction -> direction.stops().stream())
                .filter(stop -> stop.latitude() != null && stop.longitude() != null)
                .anyMatch(stop -> distanceMeters(latitude.doubleValue(), longitude.doubleValue(),
                        stop.latitude().doubleValue(), stop.longitude().doubleValue()) <= maxStopDistanceMeters);
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latitudeDelta = Math.toRadians(lat2 - lat1);
        double longitudeDelta = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(longitudeDelta / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
