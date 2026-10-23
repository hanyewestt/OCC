package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RoutePositionPolicy {
    private static final double MIN_UNCERTAINTY_METERS = 50;
    private static final double ETA_COST_SCALE_SECONDS = 120;
    private static final double MAX_ETA_COST = 8;
    private static final long FETCH_BATCH_WINDOW_SECONDS = 10;

    private RoutePositionPolicy() {
    }

    public static List<RoutePositionBus> estimate(List<RoutePositionReading> readings, double routeLengthMeters,
            Instant now, Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
            double speedCapMetersPerSecond) {
        if (routeLengthMeters <= 0 || !Double.isFinite(routeLengthMeters) || readings.isEmpty()) return List.of();
        Instant oldestAllowed = now.minus(pollInterval.multipliedBy(maxPollAgeCycles));
        Map<SnapshotKey, List<RoutePositionReading>> grouped = new LinkedHashMap<>();
        readings.stream()
                .filter(reading -> reading.snapshotId() != null && reading.observationId() != null
                        && reading.stopCode() != null && reading.fetchedAt() != null
                        && !reading.fetchedAt().isBefore(oldestAllowed) && !reading.fetchedAt().isAfter(now)
                        && Double.isFinite(reading.distanceAlongRouteM()))
                .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparingInt(RoutePositionReading::stopSequence)
                        .thenComparingInt(RoutePositionReading::visitOrder))
                .forEach(reading -> grouped.computeIfAbsent(new SnapshotKey(reading.snapshotId(), reading.stopCode(), reading.fetchedAt()),
                        ignored -> new ArrayList<>()).add(reading));

        List<Snapshot> chronological = grouped.entrySet().stream().map(entry -> {
            List<RoutePositionReading> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(RoutePositionReading::visitOrder)
                            .thenComparing(RoutePositionReading::estimatedArrivalAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(3).toList();
            return new Snapshot(entry.getKey(), ordered.getFirst().fetchedAt(), null, ordered);
        }).sorted(Comparator.comparing(Snapshot::fetchedAt))
                .toList();
        List<Snapshot> snapshots = new ArrayList<>(chronological.size());
        Instant batchStart = null;
        for (Snapshot snapshot : chronological) {
            if (batchStart == null || Duration.between(batchStart, snapshot.fetchedAt()).compareTo(
                    Duration.ofSeconds(FETCH_BATCH_WINDOW_SECONDS)) > 0) batchStart = snapshot.fetchedAt();
            snapshots.add(new Snapshot(snapshot.key(), snapshot.fetchedAt(), batchStart, snapshot.readings()));
        }
        snapshots.sort(Comparator.comparing(Snapshot::fetchBucket)
                .thenComparingInt(snapshot -> snapshot.readings().getFirst().stopSequence())
                .thenComparing(snapshot -> snapshot.key().stopCode()));
        if (snapshots.isEmpty()) return List.of();

        List<Track> tracks = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            tracks.removeIf(track -> Duration.between(track.last().fetchedAt(), snapshot.fetchedAt()).compareTo(
                    pollInterval.multipliedBy(maxPollAgeCycles)) > 0);
            List<Track> eligible = tracks.stream()
                    .filter(track -> !track.last().fetchedAt().isAfter(snapshot.fetchedAt())
                            || track.lastFetchBatch().equals(snapshot.fetchBucket())
                                    && !track.last().stopCode().equals(snapshot.key().stopCode()))
                    .sorted(Comparator.comparingDouble((Track track) -> track.projectedDistance(snapshot.fetchedAt())).reversed()
                            .thenComparingInt(track -> track.last().visitOrder()))
                    .toList();
            Assignment assignment = assign(snapshot, eligible, clusterToleranceMeters, speedCapMetersPerSecond);
            for (int candidateIndex = 0; candidateIndex < snapshot.readings().size(); candidateIndex++) {
                int trackIndex = assignment.trackByCandidate().get(candidateIndex);
                RoutePositionReading candidate = snapshot.readings().get(candidateIndex);
                if (trackIndex >= 0) {
                    Track track = eligible.get(trackIndex);
                    track.add(candidate, snapshot.fetchBucket(), speedCapMetersPerSecond);
                } else {
                    tracks.add(new Track(candidate, snapshot.fetchBucket()));
                }
            }
        }

        List<Track> active = tracks.stream()
                .filter(track -> Duration.between(track.last().fetchedAt(), now)
                        .compareTo(pollInterval.multipliedBy(maxPollAgeCycles)) <= 0)
                .sorted(Comparator.comparingDouble((Track track) -> track.projectedDistance(now)).reversed()
                        .thenComparing(track -> track.last().estimatedArrivalAt(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<RoutePositionBus> buses = new ArrayList<>(active.size());
        for (int index = 0; index < active.size(); index++) {
            Track track = active.get(index);
            buses.add(new RoutePositionBus(index + 1, clamp(track.projectedDistance(now), routeLengthMeters),
                    Math.max(MIN_UNCERTAINTY_METERS, track.positionSpreadMeters()), track.speedMps(), track.observationIds()));
        }
        return List.copyOf(buses);
    }

    private static Assignment assign(Snapshot snapshot, List<Track> tracks, double positionToleranceMeters,
            double speedCapMetersPerSecond) {
        Search best = new Search(snapshot, tracks, positionToleranceMeters, speedCapMetersPerSecond);
        best.visit(0, new ArrayList<>(), new boolean[tracks.size()], 0, 0);
        return best.best();
    }

    private static double transitionCost(RoutePositionReading previous, RoutePositionReading current,
            double positionToleranceMeters, double speedCapMetersPerSecond, Double learnedSpeed,
            boolean sameFetchBatch) {
        double seconds = Duration.between(previous.fetchedAt(), current.fetchedAt()).toMillis() / 1000.0;
        if (seconds < 0 && !sameFetchBatch || seconds == 0 && previous.stopCode().equals(current.stopCode()))
            return Double.POSITIVE_INFINITY;
        double speed = learnedSpeed == null ? 0 : learnedSpeed;
        double alignedPrevious = previous.distanceAlongRouteM() + speed * seconds;
        double positionError = Math.abs(current.distanceAlongRouteM() - alignedPrevious);
        double maximumMovement = speedCapMetersPerSecond * seconds + positionToleranceMeters * 2;
        if (positionError > maximumMovement) return Double.POSITIVE_INFINITY;

        long routeDelta = (long) current.stopSequence() - previous.stopSequence();
        double stopDistanceDelta = current.stopDistanceAlongRouteM() - previous.stopDistanceAlongRouteM();
        if (routeDelta == 0 && Double.compare(stopDistanceDelta, 0) != 0) return Double.POSITIVE_INFINITY;
        if (routeDelta != 0 && Math.signum(routeDelta) != Math.signum(stopDistanceDelta)) return Double.POSITIVE_INFINITY;
        double plausibleSpeed = learnedSpeed != null && learnedSpeed >= 2
                ? learnedSpeed : Math.max(1, speedCapMetersPerSecond / 2);
        double expectedEtaDelta = stopDistanceDelta / plausibleSpeed;
        double actualEtaDelta = previous.estimatedArrivalAt() == null || current.estimatedArrivalAt() == null ?
                expectedEtaDelta : Duration.between(previous.estimatedArrivalAt(), current.estimatedArrivalAt()).toMillis() / 1000.0;
        double etaError = Math.abs(actualEtaDelta - expectedEtaDelta);
        double etaCost = Math.min(MAX_ETA_COST, etaError / ETA_COST_SCALE_SECONDS);
        return positionError / Math.max(positionToleranceMeters, 1) + etaCost;
    }

    private static double clamp(double distance, double routeLength) {
        return Math.max(0, Math.min(distance, routeLength));
    }

    private record SnapshotKey(UUID id, String stopCode, Instant fetchedAt) { }
    private record Snapshot(SnapshotKey key, Instant fetchedAt, Instant fetchBucket, List<RoutePositionReading> readings) { }
    private record Assignment(List<Integer> trackByCandidate, int matches, double cost) { }

    private static final class Search {
        private final Snapshot snapshot;
        private final List<Track> tracks;
        private final double positionToleranceMeters;
        private final double speedCapMetersPerSecond;
        private Assignment best = new Assignment(List.of(), -1, Double.POSITIVE_INFINITY);

        private Search(Snapshot snapshot, List<Track> tracks, double positionToleranceMeters, double speedCapMetersPerSecond) {
            this.snapshot = snapshot;
            this.tracks = tracks;
            this.positionToleranceMeters = positionToleranceMeters;
            this.speedCapMetersPerSecond = speedCapMetersPerSecond;
        }

        private Assignment best() { return best; }

        private void visit(int candidateIndex, List<Integer> current, boolean[] used, int matches, double cost) {
            if (candidateIndex == snapshot.readings().size()) {
                if (matches > best.matches() || matches == best.matches() && cost < best.cost())
                    best = new Assignment(List.copyOf(current), matches, cost);
                return;
            }
            RoutePositionReading candidate = snapshot.readings().get(candidateIndex);
            current.add(-1);
            visit(candidateIndex + 1, current, used, matches, cost);
            current.removeLast();
            for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
                if (used[trackIndex] || !preservesOrder(candidateIndex, trackIndex, current)) continue;
                Track track = tracks.get(trackIndex);
                double edgeCost = transitionCost(track.last(), candidate, positionToleranceMeters,
                        speedCapMetersPerSecond, track.speedMps(),
                        track.lastFetchBatch().equals(snapshot.fetchBucket())
                                && !track.last().stopCode().equals(candidate.stopCode()));
                if (!Double.isFinite(edgeCost)) continue;
                used[trackIndex] = true;
                current.add(trackIndex);
                visit(candidateIndex + 1, current, used, matches + 1, cost + edgeCost);
                current.removeLast();
                used[trackIndex] = false;
            }
        }

        private boolean preservesOrder(int candidateIndex, int trackIndex, List<Integer> earlierAssignments) {
            RoutePositionReading candidate = snapshot.readings().get(candidateIndex);
            Track track = tracks.get(trackIndex);
            for (int earlier = 0; earlier < earlierAssignments.size(); earlier++) {
                int earlierTrackIndex = earlierAssignments.get(earlier);
                if (earlierTrackIndex < 0) continue;
                RoutePositionReading earlierCandidate = snapshot.readings().get(earlier);
                Track earlierTrack = tracks.get(earlierTrackIndex);
                if (earlierCandidate.visitOrder() < candidate.visitOrder() &&
                        earlierTrack.projectedDistance(snapshot.fetchedAt()) < track.projectedDistance(snapshot.fetchedAt())) return false;
            }
            return true;
        }
    }

    private static final class Track {
        private RoutePositionReading last;
        private RoutePositionReading previous;
        private Instant lastFetchBatch;
        private Double speedMps;
        private double positionSpreadMeters;
        private final List<UUID> observationIds = new ArrayList<>();

        private Track(RoutePositionReading first, Instant fetchBatch) {
            last = first;
            lastFetchBatch = fetchBatch;
            observationIds.add(first.observationId());
        }

        private void add(RoutePositionReading reading, Instant fetchBatch, double speedCapMetersPerSecond) {
            previous = last;
            double elapsed = Duration.between(previous.fetchedAt(), reading.fetchedAt()).toMillis() / 1000.0;
            double advance = reading.distanceAlongRouteM() - previous.distanceAlongRouteM();
            if (elapsed > 0 && advance >= 0 && advance <= speedCapMetersPerSecond * elapsed)
                speedMps = advance / elapsed;
            positionSpreadMeters = Math.abs(reading.distanceAlongRouteM() - previous.distanceAlongRouteM()) / 2;
            last = reading;
            lastFetchBatch = fetchBatch;
            observationIds.add(reading.observationId());
            if (observationIds.size() > 12) observationIds.removeFirst();
        }

        private RoutePositionReading last() { return last; }
        private Double speedMps() { return speedMps; }
        private Instant lastFetchBatch() { return lastFetchBatch; }
        private List<UUID> observationIds() { return List.copyOf(observationIds); }
        private double projectedDistance(Instant now) {
            double seconds = Math.max(0, Duration.between(last.fetchedAt(), now).toMillis() / 1000.0);
            return last.distanceAlongRouteM() + (speedMps == null ? 0 : speedMps * seconds);
        }
        private double positionSpreadMeters() { return positionSpreadMeters; }
    }
}
