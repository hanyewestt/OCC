package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class RoutePositionPolicy {
    private static final double MIN_UNCERTAINTY_METERS = 50;
    private static final double ETA_COST_SCALE_SECONDS = 120;
    private static final double MAX_ETA_COST = 8;
    private static final int MAX_EVIDENCE_OBSERVATIONS = 64;
    private RoutePositionPolicy() {
    }

    public static List<RoutePositionBus> estimate(List<RoutePositionReading> readings, double routeLengthMeters,
            Instant now, Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
            double speedCapMetersPerSecond) {
        return estimateWithConfidence(readings, routeLengthMeters, now, pollInterval, maxPollAgeCycles,
                clusterToleranceMeters, speedCapMetersPerSecond).buses();
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings, double routeLengthMeters,
            Instant now, Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
            double speedCapMetersPerSecond) {
        if (routeLengthMeters <= 0 || !Double.isFinite(routeLengthMeters) || readings.isEmpty())
            return new RoutePositionEstimate(List.of(), 0);
        Instant oldestAllowed = now.minus(pollInterval.multipliedBy(maxPollAgeCycles));
        Map<SnapshotKey, List<RoutePositionReading>> grouped = new LinkedHashMap<>();
        readings.stream()
                .filter(reading -> reading.snapshotId() != null && reading.pollCycleId() != null && reading.observationId() != null
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
                    .toList();
            return new Snapshot(entry.getKey(), ordered.getFirst().pollCycleId(), ordered.getFirst().fetchedAt(), null, ordered);
        }).sorted(Comparator.comparing(Snapshot::fetchedAt))
                .toList();
        Map<UUID, Instant> cycleStarts = new LinkedHashMap<>();
        chronological.forEach(snapshot -> cycleStarts.merge(snapshot.pollCycleId(), snapshot.fetchedAt(),
                (first, next) -> first.isBefore(next) ? first : next));
        List<Snapshot> snapshots = new ArrayList<>(chronological.size());
        for (Snapshot snapshot : chronological) {
            snapshots.add(new Snapshot(snapshot.key(), snapshot.pollCycleId(), snapshot.fetchedAt(),
                    cycleStarts.get(snapshot.pollCycleId()), snapshot.readings()));
        }
        snapshots.sort(Comparator.comparing(Snapshot::pollCycleStartedAt)
                .thenComparingInt(snapshot -> snapshot.readings().getFirst().stopSequence())
                .thenComparing(snapshot -> snapshot.key().stopCode()));
        if (snapshots.isEmpty()) return new RoutePositionEstimate(List.of(), 0);
        Snapshot latestSnapshot = snapshots.stream()
                .max(Comparator.comparing(Snapshot::pollCycleStartedAt)
                        .thenComparing(Snapshot::fetchedAt))
                .orElseThrow();
        UUID latestPollCycleId = latestSnapshot.pollCycleId();
        int cycleCandidateCeiling = snapshots.stream()
                .filter(snapshot -> snapshot.pollCycleId().equals(latestPollCycleId))
                .mapToInt(snapshot -> snapshot.readings().size())
                .max()
                .orElse(0);

        List<Track> tracks = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            tracks.removeIf(track -> Duration.between(track.last().fetchedAt(), snapshot.fetchedAt()).compareTo(
                    pollInterval.multipliedBy(maxPollAgeCycles)) > 0);
            List<Track> eligible = tracks.stream()
                    .filter(track -> !track.last().fetchedAt().isAfter(snapshot.fetchedAt())
                            || track.lastPollCycleId().equals(snapshot.pollCycleId())
                                    && !track.last().stopCode().equals(snapshot.key().stopCode()))
                    .sorted(Comparator.comparingDouble((Track track) -> track.projectedDistance(snapshot.fetchedAt())).reversed()
                            .thenComparingInt(track -> track.last().visitOrder()))
                    .toList();
            Assignment assignment = assign(snapshot, eligible, clusterToleranceMeters, speedCapMetersPerSecond, routeLengthMeters);
            for (int candidateIndex = 0; candidateIndex < snapshot.readings().size(); candidateIndex++) {
                int trackIndex = assignment.trackByCandidate().get(candidateIndex);
                RoutePositionReading candidate = snapshot.readings().get(candidateIndex);
                if (trackIndex >= 0) {
                    Track track = eligible.get(trackIndex);
                    boolean sameFetchBatch = track.lastPollCycleId().equals(snapshot.pollCycleId())
                            && !track.last().stopCode().equals(candidate.stopCode());
                    track.add(candidate, snapshot.pollCycleId(), speedCapMetersPerSecond,
                            clusterToleranceMeters, sameFetchBatch);
                } else {
                    tracks.add(new Track(candidate, snapshot.pollCycleId()));
                }
            }
        }

        List<Track> active = tracks.stream()
                .filter(track -> Duration.between(track.last().fetchedAt(), now)
                        .compareTo(pollInterval.multipliedBy(maxPollAgeCycles)) <= 0)
                .toList();
        List<Track> latestCycleTracks = active.stream()
                .filter(track -> track.lastPollCycleId().equals(latestPollCycleId))
                .toList();
        List<Track> wellSupported = latestCycleTracks.stream()
                .filter(track -> track.supportingStopCount() >= 2)
                .toList();
        List<Track> selectedTracks = !wellSupported.isEmpty()
                ? wellSupported
                : latestCycleTracks.stream()
                        .sorted(Comparator.comparingInt(Track::supportingStopCount).reversed()
                                .thenComparing(Comparator.comparingInt(Track::observationCount).reversed())
                                .thenComparingDouble((Track track) -> track.projectedDistance(now)).reversed()
                                .thenComparing(track -> track.last().estimatedArrivalAt(), Comparator.nullsLast(Comparator.naturalOrder())))
                        .limit(cycleCandidateCeiling > 0 ? cycleCandidateCeiling : latestCycleTracks.size())
                        .toList();
        List<Track> selected = selectedTracks.stream()
                .sorted(Comparator.comparingDouble((Track track) -> track.projectedDistance(now)).reversed()
                        .thenComparing(track -> track.last().estimatedArrivalAt(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<RoutePositionBus> buses = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            Track track = selected.get(index);
            buses.add(new RoutePositionBus(index + 1, clamp(track.projectedDistance(now), routeLengthMeters),
                    Math.max(MIN_UNCERTAINTY_METERS, track.positionSpreadMeters()), track.speedMps(),
                    track.observationIds(), track.supportingStopCount()));
        }
        return new RoutePositionEstimate(List.copyOf(buses), active.size() - selected.size());
    }

    public record RoutePositionEstimate(List<RoutePositionBus> buses, int suppressedBusCount) {
        public RoutePositionEstimate {
            buses = List.copyOf(buses);
        }
    }

    private static Assignment assign(Snapshot snapshot, List<Track> tracks, double positionToleranceMeters,
            double speedCapMetersPerSecond, double routeLengthMeters) {
        Search best = new Search(snapshot, tracks, positionToleranceMeters, speedCapMetersPerSecond, routeLengthMeters);
        best.visit(0, new ArrayList<>(), new boolean[tracks.size()], 0, 0);
        return best.best();
    }

    private static double transitionCost(RoutePositionReading previous, RoutePositionReading current,
            double positionToleranceMeters, double speedCapMetersPerSecond, Double learnedSpeed,
            boolean sameFetchBatch, double routeLengthMeters) {
        double seconds = Duration.between(previous.fetchedAt(), current.fetchedAt()).toMillis() / 1000.0;
        if (seconds < 0 && !sameFetchBatch || seconds == 0 && previous.stopCode().equals(current.stopCode()))
            return Double.POSITIVE_INFINITY;
        double speed = learnedSpeed == null ? 0 : learnedSpeed;
        double alignedPrevious = previous.distanceAlongRouteM() + speed * seconds;
        double positionError = Math.abs(current.distanceAlongRouteM() - alignedPrevious);
        long routeDelta = (long) current.stopSequence() - previous.stopSequence();
        double stopDistanceDelta = current.stopDistanceAlongRouteM() - previous.stopDistanceAlongRouteM();
        if (routeDelta == 0 && Double.compare(stopDistanceDelta, 0) != 0) return Double.POSITIVE_INFINITY;
        if (routeDelta != 0 && Math.signum(routeDelta) != Math.signum(stopDistanceDelta)) return Double.POSITIVE_INFINITY;
        double maximumMovement = sameFetchBatch
                ? Math.abs(stopDistanceDelta) + positionToleranceMeters * 2
                : speedCapMetersPerSecond * seconds + positionToleranceMeters * 2;
        if (!sameFetchBatch && positionError > maximumMovement) return Double.POSITIVE_INFINITY;
        if (sameFetchBatch && positionError > Math.max(maximumMovement * 2, 2500.0)
                && current.distanceAlongRouteM() <= routeLengthMeters && previous.distanceAlongRouteM() <= routeLengthMeters) {
            return Double.POSITIVE_INFINITY;
        }
        double plausibleSpeed = learnedSpeed != null && learnedSpeed >= 2
                ? learnedSpeed : Math.max(1, speedCapMetersPerSecond / 2);
        double expectedEtaDelta = stopDistanceDelta / plausibleSpeed;
        double actualEtaDelta = previous.estimatedArrivalAt() == null || current.estimatedArrivalAt() == null ?
                expectedEtaDelta : Duration.between(previous.estimatedArrivalAt(), current.estimatedArrivalAt()).toMillis() / 1000.0;
        double etaError = Math.abs(actualEtaDelta - expectedEtaDelta);
        double etaCost = Math.min(MAX_ETA_COST, etaError / ETA_COST_SCALE_SECONDS);
        double positionCost = positionError / Math.max(positionToleranceMeters, 1);
        double orderCost = current.visitOrder() == previous.visitOrder() ? 0 : 0.5;
        return positionCost + etaCost + orderCost;
    }

    private static double clamp(double distance, double routeLength) {
        return Math.max(0, Math.min(distance, routeLength));
    }

    private record SnapshotKey(UUID id, String stopCode, Instant fetchedAt) { }
    private record Snapshot(SnapshotKey key, UUID pollCycleId, Instant fetchedAt, Instant pollCycleStartedAt,
                            List<RoutePositionReading> readings) { }
    private record Assignment(List<Integer> trackByCandidate, int matches, double cost) { }

    private static final class Search {
        private final Snapshot snapshot;
        private final List<Track> tracks;
        private final double positionToleranceMeters;
        private final double speedCapMetersPerSecond;
        private final double routeLengthMeters;
        private Assignment best = new Assignment(List.of(), -1, Double.POSITIVE_INFINITY);

        private Search(Snapshot snapshot, List<Track> tracks, double positionToleranceMeters,
                double speedCapMetersPerSecond, double routeLengthMeters) {
            this.snapshot = snapshot;
            this.tracks = tracks;
            this.positionToleranceMeters = positionToleranceMeters;
            this.speedCapMetersPerSecond = speedCapMetersPerSecond;
            this.routeLengthMeters = routeLengthMeters;
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
                        track.lastPollCycleId().equals(snapshot.pollCycleId())
                                && !track.last().stopCode().equals(candidate.stopCode()),
                        routeLengthMeters);
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
        private UUID lastPollCycleId;
        private Double speedMps;
        private double positionSpreadMeters;
        private final List<UUID> observationIds = new ArrayList<>();
        private final Set<String> stopCodes = new HashSet<>();

        private Track(RoutePositionReading first, UUID pollCycleId) {
            last = first;
            lastPollCycleId = pollCycleId;
            observationIds.add(first.observationId());
            stopCodes.add(first.stopCode());
        }

        private void add(RoutePositionReading reading, UUID pollCycleId, double speedCapMetersPerSecond,
                double positionToleranceMeters, boolean sameFetchBatch) {
            previous = last;
            double elapsed = Duration.between(previous.fetchedAt(), reading.fetchedAt()).toMillis() / 1000.0;
            double distance = reading.distanceAlongRouteM();
            if (sameFetchBatch) {
                double maximumMovement = Math.abs(reading.stopDistanceAlongRouteM() - previous.stopDistanceAlongRouteM())
                        + positionToleranceMeters * 2;
                distance = Math.max(previous.distanceAlongRouteM() - maximumMovement,
                        Math.min(previous.distanceAlongRouteM() + maximumMovement, distance));
            }
            RoutePositionReading effectiveReading = distance == reading.distanceAlongRouteM() ? reading :
                    new RoutePositionReading(reading.observationId(), reading.snapshotId(), reading.pollCycleId(),
                            reading.stopCode(), reading.stopSequence(), reading.visitOrder(), distance,
                            reading.stopDistanceAlongRouteM(), reading.estimatedArrivalAt(), reading.fetchedAt());
            double advance = effectiveReading.distanceAlongRouteM() - previous.distanceAlongRouteM();
            if (elapsed > 0 && advance >= 0 && advance <= speedCapMetersPerSecond * elapsed)
                speedMps = advance / elapsed;
            positionSpreadMeters = Math.abs(effectiveReading.distanceAlongRouteM() - previous.distanceAlongRouteM()) / 2;
            last = effectiveReading;
            lastPollCycleId = pollCycleId;
            observationIds.add(reading.observationId());
            stopCodes.add(reading.stopCode());
            if (observationIds.size() > MAX_EVIDENCE_OBSERVATIONS) observationIds.removeFirst();
        }

        private RoutePositionReading last() { return last; }
        private Double speedMps() { return speedMps; }
        private UUID lastPollCycleId() { return lastPollCycleId; }
        private List<UUID> observationIds() { return List.copyOf(observationIds); }
        private int supportingStopCount() { return stopCodes.size(); }
        private int observationCount() { return observationIds.size(); }
        private double projectedDistance(Instant now) {
            double seconds = Math.max(0, Duration.between(last.fetchedAt(), now).toMillis() / 1000.0);
            return last.distanceAlongRouteM() + (speedMps == null ? 0 : speedMps * seconds);
        }
        private double positionSpreadMeters() { return positionSpreadMeters; }
    }
}
