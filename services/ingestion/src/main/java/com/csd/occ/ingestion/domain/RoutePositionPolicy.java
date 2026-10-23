package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoutePositionPolicy {
    private static final double MIN_UNCERTAINTY_METERS = 50;

    private RoutePositionPolicy() {
    }

    public static List<RoutePositionBus> estimate(List<RoutePositionReading> readings, double routeLengthMeters,
            Instant now, Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
            double speedCapMetersPerSecond) {
        if (routeLengthMeters <= 0 || !Double.isFinite(routeLengthMeters) || readings.isEmpty()) return List.of();
        Instant oldestAllowed = now.minus(pollInterval.multipliedBy(maxPollAgeCycles));
        List<RoutePositionReading> fresh = readings.stream()
                .filter(reading -> reading.fetchedAt() != null && !reading.fetchedAt().isBefore(oldestAllowed)
                        && !reading.fetchedAt().isAfter(now))
                .map(reading -> new RoutePositionReading(clamp(reading.distanceAlongRouteM(), routeLengthMeters),
                        reading.fetchedAt()))
                .toList();
        if (fresh.isEmpty()) return List.of();

        Instant latest = fresh.stream().map(RoutePositionReading::fetchedAt).max(Instant::compareTo).orElseThrow();
        Instant currentCutoff = latest.minus(pollInterval);
        Instant previousCutoff = latest.minus(pollInterval.multipliedBy(2));
        List<Cluster> current = new ArrayList<>(cluster(fresh.stream()
                .filter(reading -> !reading.fetchedAt().isBefore(currentCutoff)).toList(), clusterToleranceMeters));
        List<Cluster> previous = cluster(fresh.stream()
                .filter(reading -> reading.fetchedAt().isBefore(currentCutoff)
                        && !reading.fetchedAt().isBefore(previousCutoff)).toList(), clusterToleranceMeters);

        current.sort(Comparator.comparingDouble(Cluster::distanceMeters).reversed());
        List<RoutePositionBus> buses = new ArrayList<>(current.size());
        boolean[] matched = new boolean[previous.size()];
        for (int index = 0; index < current.size(); index++) {
            Cluster cluster = current.get(index);
            Double speed = matchPrevious(cluster, previous, matched, pollInterval, speedCapMetersPerSecond);
            buses.add(new RoutePositionBus(index + 1, cluster.distanceMeters(),
                    Math.max(MIN_UNCERTAINTY_METERS, (cluster.maximumMeters() - cluster.minimumMeters()) / 2), speed));
        }
        return List.copyOf(buses);
    }

    private static Double matchPrevious(Cluster current, List<Cluster> previous, boolean[] matched,
            Duration pollInterval, double speedCapMetersPerSecond) {
        int matchIndex = -1;
        double smallestAdvance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < previous.size(); index++) {
            if (matched[index]) continue;
            Cluster old = previous.get(index);
            double elapsed = Duration.between(old.representativeTime(), current.representativeTime()).toMillis() / 1000.0;
            double advance = current.distanceMeters() - old.distanceMeters();
            if (elapsed <= 0 || advance < 0 || advance > speedCapMetersPerSecond * elapsed) continue;
            if (advance < smallestAdvance) {
                smallestAdvance = advance;
                matchIndex = index;
            }
        }
        if (matchIndex < 0) return null;
        Cluster old = previous.get(matchIndex);
        double elapsed = Duration.between(old.representativeTime(), current.representativeTime()).toMillis() / 1000.0;
        if (elapsed > pollInterval.toMillis() * 2 / 1000.0) return null;
        matched[matchIndex] = true;
        return smallestAdvance / elapsed;
    }

    private static List<Cluster> cluster(List<RoutePositionReading> readings, double toleranceMeters) {
        List<RoutePositionReading> sorted = readings.stream()
                .sorted(Comparator.comparingDouble(RoutePositionReading::distanceAlongRouteM)).toList();
        List<Cluster> clusters = new ArrayList<>();
        List<RoutePositionReading> members = new ArrayList<>();
        double clusterStart = Double.NaN;
        for (RoutePositionReading reading : sorted) {
            if (!members.isEmpty() && reading.distanceAlongRouteM() - clusterStart > toleranceMeters) {
                clusters.add(cluster(members));
                members = new ArrayList<>();
            }
            if (members.isEmpty()) clusterStart = reading.distanceAlongRouteM();
            members.add(reading);
        }
        if (!members.isEmpty()) clusters.add(cluster(members));
        return clusters;
    }

    private static Cluster cluster(List<RoutePositionReading> members) {
        List<Double> distances = members.stream().map(RoutePositionReading::distanceAlongRouteM).sorted().toList();
        List<Instant> times = members.stream().map(RoutePositionReading::fetchedAt).sorted().toList();
        return new Cluster(median(distances), distances.getFirst(), distances.getLast(), times.get(times.size() / 2));
    }

    private static double median(List<Double> values) {
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }

    private static double clamp(double distance, double routeLength) {
        return Math.max(0, Math.min(distance, routeLength));
    }

    private record Cluster(double distanceMeters, double minimumMeters, double maximumMeters, Instant representativeTime) {
    }
}
