package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RoutePositionPolicy {
    private static final double DEFAULT_UNCERTAINTY_METERS = 50;
    private static final double DEFAULT_CIRCULAR_PROJECTION_SPEED_METERS_PER_SECOND = 7;
    private static final int DEFAULT_CIRCULAR_PROJECTION_MINIMUM_STOP_COUNT = 3;
    private static final double DEFAULT_IDENTITY_LINK_DISTANCE_METERS = 25;
    private static final double DEFAULT_IDENTITY_LINK_PROJECTION_SPEED_METERS_PER_SECOND = 7;
    private static final Duration DEFAULT_IDENTITY_ETA_TOLERANCE = Duration.ofSeconds(20);
    private static final double DEFAULT_REFRESH_BOUNDARY_DISTANCE_METERS = 250;
    private static final Duration DEFAULT_REFRESH_BOUNDARY_WINDOW = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REFRESH_BOUNDARY_ALLOWED_OVERLAP = Duration.ofMillis(250);
    private static final Duration DEFAULT_REFRESH_BOUNDARY_PROFILE_RESIDUAL = Duration.ofSeconds(75);
    private static final double DEFAULT_REFRESH_BOUNDARY_PROFILE_MIN_SECONDS_PER_METER = 0.02;
    private static final double DEFAULT_REFRESH_BOUNDARY_PROFILE_MAX_SECONDS_PER_METER = 2;
    private static final double DEFAULT_CIRCULAR_PHASE_LINK_DISTANCE_METERS = 250;
    private static final double DEFAULT_CIRCULAR_PHASE_MAXIMUM_GAP_METERS = 2_000;
    private static final Duration DEFAULT_CIRCULAR_PHASE_MAXIMUM_ETA_ERROR = Duration.ofSeconds(75);

    private RoutePositionPolicy() {
    }

    public static List<RoutePositionBus> estimate(List<RoutePositionReading> readings, double routeLengthMeters,
            Instant now, Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
            double speedCapMetersPerSecond) {
        return estimateWithConfidence(readings, routeLengthMeters, now, pollInterval, maxPollAgeCycles,
                clusterToleranceMeters, speedCapMetersPerSecond).buses();
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, Instant now, Duration pollInterval, int maxPollAgeCycles,
            double clusterToleranceMeters, double speedCapMetersPerSecond) {
        Parameters parameters = new Parameters(pollInterval, maxPollAgeCycles, clusterToleranceMeters,
                speedCapMetersPerSecond, DEFAULT_UNCERTAINTY_METERS,
                DEFAULT_CIRCULAR_PROJECTION_SPEED_METERS_PER_SECOND,
                DEFAULT_CIRCULAR_PROJECTION_MINIMUM_STOP_COUNT);
        return estimateWithConfidence(readings, routeLengthMeters, false, routeLengthMeters, now, parameters);
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, boolean circular, double referenceLengthMeters, Instant now,
            Parameters parameters) {
        return estimateWithConfidence(readings, routeLengthMeters, circular, referenceLengthMeters, now,
                parameters, false);
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, boolean circular, double referenceLengthMeters, Instant now,
            Parameters parameters, boolean coordinatedRefreshBoundaryEnabled) {
        return estimateWithConfidence(readings, routeLengthMeters, circular, referenceLengthMeters, now,
                parameters, true, coordinatedRefreshBoundaryEnabled, true);
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, boolean circular, double referenceLengthMeters, Instant now,
            Parameters parameters, boolean identityLinkEnabled, boolean coordinatedRefreshBoundaryEnabled,
            boolean circularPhaseLinkEnabled) {
        return estimateWithConfidence(readings, routeLengthMeters, circular, referenceLengthMeters, now,
                parameters, Optional.empty(), identityLinkEnabled, coordinatedRefreshBoundaryEnabled,
                circularPhaseLinkEnabled);
    }

    public static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, boolean circular, double referenceLengthMeters, Instant now,
            Parameters parameters, UUID selectedPollCycleId, boolean identityLinkEnabled,
            boolean coordinatedRefreshBoundaryEnabled, boolean circularPhaseLinkEnabled) {
        return estimateWithConfidence(readings, routeLengthMeters, circular, referenceLengthMeters, now,
                parameters, Optional.of(Objects.requireNonNull(selectedPollCycleId)), identityLinkEnabled,
                coordinatedRefreshBoundaryEnabled, circularPhaseLinkEnabled);
    }

    private static RoutePositionEstimate estimateWithConfidence(List<RoutePositionReading> readings,
            double routeLengthMeters, boolean circular, double referenceLengthMeters, Instant now,
            Parameters parameters, Optional<UUID> selectedPollCycleId, boolean identityLinkEnabled,
            boolean coordinatedRefreshBoundaryEnabled, boolean circularPhaseLinkEnabled) {
        Objects.requireNonNull(readings);
        Objects.requireNonNull(now);
        Objects.requireNonNull(parameters);
        if (readings.isEmpty() || !isPositiveFinite(routeLengthMeters)) {
            return new RoutePositionEstimate(List.of(), 0);
        }

        Instant oldestAllowed = now.minus(parameters.pollInterval().multipliedBy(parameters.maxPollAgeCycles()));
        Map<UUID, RoutePositionReading> uniqueReadings = new LinkedHashMap<>();
        readings.stream()
                .filter(reading -> isUsable(reading, oldestAllowed, now))
                .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparing(RoutePositionReading::observationId))
                .forEach(reading -> uniqueReadings.putIfAbsent(reading.observationId(), reading));
        if (uniqueReadings.isEmpty()) return new RoutePositionEstimate(List.of(), 0);
        Set<IdentityPair> coexistencePairs = coexistencePairs(uniqueReadings.values());

        Map<UUID, Instant> cycleNewestReading = new LinkedHashMap<>();
        uniqueReadings.values().forEach(reading -> cycleNewestReading.merge(reading.pollCycleId(),
                reading.fetchedAt(), (first, next) -> first.isAfter(next) ? first : next));
        UUID displayedPollCycleId = selectedPollCycleId.orElseGet(() -> cycleNewestReading.entrySet().stream()
                .max(Comparator.<Map.Entry<UUID, Instant>, Instant>comparing(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().toString()))
                .orElseThrow()
                .getKey());

        Map<IdentityKey, List<RoutePositionReading>> readingsByIdentity = new LinkedHashMap<>();
        uniqueReadings.values().stream()
                .filter(reading -> reading.pollCycleId().equals(displayedPollCycleId))
                .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparing(RoutePositionReading::snapshotId)
                        .thenComparingInt(RoutePositionReading::visitOrder)
                        .thenComparing(RoutePositionReading::observationId))
                .forEach(reading -> readingsByIdentity
                        .computeIfAbsent(IdentityKey.from(reading), ignored -> new ArrayList<>())
                        .add(reading));

        List<IdentityGroup> identityGroups = readingsByIdentity.entrySet().stream()
                .map(entry -> IdentityGroup.from(entry.getKey(), entry.getValue(), parameters))
                .toList();
        List<EstimatedBus> estimates = circular
                ? estimateCircular(identityGroups, routeLengthMeters, referenceLengthMeters, parameters,
                        circularPhaseLinkEnabled, coexistencePairs)
                : estimateOpen(identityGroups, routeLengthMeters, parameters,
                        identityLinkEnabled, coordinatedRefreshBoundaryEnabled, coexistencePairs);

        estimates.sort(Comparator.comparingDouble(EstimatedBus::distanceAlongRouteM).reversed()
                .thenComparing(estimate -> estimate.identity().sortValue())
                .thenComparing(estimate -> estimate.evidenceObservationIds().getFirst()));
        List<RoutePositionBus> buses = new ArrayList<>(estimates.size());
        for (int index = 0; index < estimates.size(); index++) {
            EstimatedBus estimate = estimates.get(index);
            buses.add(new RoutePositionBus(index + 1, estimate.distanceAlongRouteM(), estimate.uncertaintyM(), null,
                    estimate.evidenceObservationIds(), estimate.positionEvidenceObservationIds(),
                    estimate.supportingStopCount()));
        }
        return new RoutePositionEstimate(List.copyOf(buses), 0);
    }

    private static List<EstimatedBus> estimateOpen(List<IdentityGroup> identityGroups,
            double routeLengthMeters, Parameters parameters, boolean identityLinkEnabled,
            boolean coordinatedRefreshBoundaryEnabled, Set<IdentityPair> coexistencePairs) {
        List<IdentityCluster> clusters = identityLinkEnabled
                ? linkNearbyIdentities(identityGroups, parameters, coexistencePairs)
                : identityGroups.stream().map(group -> new IdentityCluster(List.of(group))).toList();
        if (coordinatedRefreshBoundaryEnabled) {
            clusters = applyCoordinatedRefreshBoundaries(clusters, identityGroups, parameters, coexistencePairs);
        }
        List<EstimatedBus> estimates = new ArrayList<>();
        for (IdentityCluster cluster : clusters) {
            for (List<RoutePositionReading> busEvidence : preserveSnapshotMultiplicity(cluster.readings())) {
                List<RoutePositionReading> freshestIdentity = freshestIdentityEvidence(busEvidence);
                double distance = freshestIdentity.stream()
                        .mapToDouble(RoutePositionReading::distanceAlongRouteM)
                        .average()
                        .orElseGet(() -> busEvidence.stream()
                                .mapToDouble(RoutePositionReading::distanceAlongRouteM).average().orElse(0));
                List<RoutePositionReading> positionEvidence = freshestIdentity.isEmpty()
                        ? busEvidence
                        : freshestIdentity;
                estimates.add(estimatedBus(clamp(distance, routeLengthMeters), busEvidence, positionEvidence,
                        busEvidence, cluster.sortIdentity(), parameters));
            }
        }
        return estimates;
    }

    private static List<EstimatedBus> estimateCircular(List<IdentityGroup> identityGroups,
            double routeLengthMeters, double referenceLengthMeters, Parameters parameters,
            boolean circularPhaseLinkEnabled, Set<IdentityPair> coexistencePairs) {
        Map<IdentityKey, List<RoutePositionReading>> occurrenceEvidence = circularPhaseLinkEnabled
                ? circularOccurrenceEvidence(identityGroups, referenceLengthMeters, parameters, coexistencePairs)
                : Map.of();
        List<EstimatedBus> estimates = new ArrayList<>();
        for (IdentityGroup group : identityGroups) {
            List<RoutePositionReading> scoringEvidence = occurrenceEvidence.getOrDefault(group.identity(),
                    group.readings());
            double distance = selectDistance(scoringEvidence, group.readings(), routeLengthMeters,
                    referenceLengthMeters, parameters);
            for (List<RoutePositionReading> busEvidence : preserveSnapshotMultiplicity(group.readings())) {
                estimates.add(estimatedBus(distance, scoringEvidence, busEvidence, busEvidence,
                        group.identity(), parameters));
            }
        }
        return estimates;
    }

    private static EstimatedBus estimatedBus(double distance, List<RoutePositionReading> auditEvidence,
            List<RoutePositionReading> positionEvidence, List<RoutePositionReading> supportingEvidence,
            IdentityKey identity, Parameters parameters) {
        List<UUID> evidenceIds = observationIds(auditEvidence);
        List<UUID> positionEvidenceIds = observationIds(positionEvidence);
        int supportingStopCount = (int) supportingEvidence.stream()
                .map(RoutePositionReading::stopCode)
                .distinct()
                .count();
        return new EstimatedBus(distance, parameters.uncertaintyMeters(), evidenceIds,
                positionEvidenceIds, supportingStopCount, identity);
    }

    private static List<UUID> observationIds(List<RoutePositionReading> readings) {
        return readings.stream()
                .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                        .thenComparing(RoutePositionReading::observationId))
                .map(RoutePositionReading::observationId)
                .distinct()
                .toList();
    }

    private static boolean isUsable(RoutePositionReading reading, Instant oldestAllowed, Instant now) {
        return reading != null && reading.observationId() != null && reading.snapshotId() != null
                && reading.pollCycleId() != null && reading.stopCode() != null && !reading.stopCode().isBlank()
                && reading.fetchedAt() != null && !reading.fetchedAt().isBefore(oldestAllowed)
                && !reading.fetchedAt().isAfter(now) && Double.isFinite(reading.distanceAlongRouteM());
    }

    private static Set<IdentityPair> coexistencePairs(Iterable<RoutePositionReading> readings) {
        Map<CycleSnapshot, Set<IdentityKey>> identitiesBySnapshot = new LinkedHashMap<>();
        for (RoutePositionReading reading : readings) {
            CycleSnapshot snapshot = new CycleSnapshot(reading.pollCycleId(), reading.snapshotId());
            identitiesBySnapshot.computeIfAbsent(snapshot, ignored -> new LinkedHashSet<>())
                    .add(IdentityKey.from(reading));
        }
        Set<IdentityPair> pairs = new LinkedHashSet<>();
        for (Set<IdentityKey> snapshotIdentities : identitiesBySnapshot.values()) {
            List<IdentityKey> identities = snapshotIdentities.stream()
                    .sorted(Comparator.comparing(IdentityKey::sortValue))
                    .toList();
            for (int firstIndex = 0; firstIndex < identities.size(); firstIndex++) {
                for (int secondIndex = firstIndex + 1; secondIndex < identities.size(); secondIndex++) {
                    IdentityKey first = identities.get(firstIndex);
                    IdentityKey second = identities.get(secondIndex);
                    if (first.sameJourney(second)) pairs.add(IdentityPair.of(first, second));
                }
            }
        }
        return Set.copyOf(pairs);
    }

    private static List<IdentityCluster> linkNearbyIdentities(List<IdentityGroup> identityGroups,
            Parameters parameters, Set<IdentityPair> coexistencePairs) {
        List<IdentityCluster> clusters = identityGroups.stream()
                .map(group -> new IdentityCluster(List.of(group)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        while (true) {
            ClusterPair closestPair = null;
            for (int firstIndex = 0; firstIndex < clusters.size(); firstIndex++) {
                for (int secondIndex = firstIndex + 1; secondIndex < clusters.size(); secondIndex++) {
                    ClusterDistance distance = clusterDistance(clusters.get(firstIndex), clusters.get(secondIndex),
                            parameters, coexistencePairs);
                    if (distance == null) continue;
                    ClusterPair candidate = new ClusterPair(firstIndex, secondIndex, distance);
                    if (closestPair == null || candidate.isBefore(closestPair)) closestPair = candidate;
                }
            }
            if (closestPair == null) return List.copyOf(clusters);
            IdentityCluster first = clusters.get(closestPair.firstIndex());
            IdentityCluster second = clusters.get(closestPair.secondIndex());
            List<IdentityGroup> mergedGroups = new ArrayList<>(first.groups());
            mergedGroups.addAll(second.groups());
            clusters.remove(closestPair.secondIndex());
            clusters.remove(closestPair.firstIndex());
            clusters.add(new IdentityCluster(List.copyOf(mergedGroups)));
        }
    }

    private static ClusterDistance clusterDistance(IdentityCluster first, IdentityCluster second,
            Parameters parameters, Set<IdentityPair> coexistencePairs) {
        double minimumDistance = Double.POSITIVE_INFINITY;
        double maximumDistance = 0;
        for (IdentityGroup firstGroup : first.groups()) {
            for (IdentityGroup secondGroup : second.groups()) {
                if (!firstGroup.identity().sameJourney(secondGroup.identity())
                        || sharesSnapshot(firstGroup, secondGroup)
                        || coexistencePairs.contains(IdentityPair.of(
                                firstGroup.identity(), secondGroup.identity()))) {
                    return null;
                }
                double distance = haversineMeters(firstGroup, secondGroup);
                if (!Double.isFinite(distance) || distance > parameters.identityLinkDistanceMeters()) return null;
                double etaGapSeconds = Math.abs(firstGroup.etaInterceptSeconds()
                        - secondGroup.etaInterceptSeconds());
                if (!Double.isFinite(etaGapSeconds)
                        || etaGapSeconds > seconds(parameters.identityEtaTolerance())) {
                    return null;
                }
                minimumDistance = Math.min(minimumDistance, distance);
                maximumDistance = Math.max(maximumDistance, distance);
            }
        }
        return new ClusterDistance(minimumDistance, maximumDistance);
    }

    private static List<IdentityCluster> applyCoordinatedRefreshBoundaries(List<IdentityCluster> linkedClusters,
            List<IdentityGroup> identityGroups, Parameters parameters, Set<IdentityPair> coexistencePairs) {
        Map<UUID, List<IdentityGroup>> singletonGroupsBySnapshot = new LinkedHashMap<>();
        for (IdentityGroup group : identityGroups) {
            if (group.readings().size() != 1) continue;
            UUID snapshotId = group.readings().getFirst().snapshotId();
            singletonGroupsBySnapshot.computeIfAbsent(snapshotId, ignored -> new ArrayList<>()).add(group);
        }

        List<BoundaryMatch> matches = new ArrayList<>();
        Set<IdentityKey> usedOldGroups = new LinkedHashSet<>();
        Set<IdentityKey> usedNewGroups = new LinkedHashSet<>();
        for (List<IdentityGroup> singletonGroups : singletonGroupsBySnapshot.values()) {
            if (singletonGroups.size() < 2) continue;
            List<BoundaryMatch> proposed = new ArrayList<>();
            for (IdentityGroup newGroup : singletonGroups) {
                List<IdentityGroup> eligibleOldGroups = identityGroups.stream()
                        .filter(oldGroup -> oldGroup != newGroup && oldGroup.readings().size() >= 3)
                        .filter(oldGroup -> boundaryCompatible(
                                oldGroup, newGroup, parameters, coexistencePairs))
                        .toList();
                if (eligibleOldGroups.size() == 1) {
                    proposed.add(new BoundaryMatch(eligibleOldGroups.getFirst(), newGroup));
                }
            }
            Map<IdentityKey, Integer> matchesPerOldGroup = new LinkedHashMap<>();
            proposed.forEach(match -> matchesPerOldGroup.merge(match.oldGroup().identity(), 1, Integer::sum));
            List<BoundaryMatch> uniqueMatches = proposed.stream()
                    .filter(match -> matchesPerOldGroup.get(match.oldGroup().identity()) == 1)
                    .toList();
            if (uniqueMatches.size() < 2) continue;
            for (BoundaryMatch match : uniqueMatches) {
                if (usedOldGroups.add(match.oldGroup().identity())
                        && usedNewGroups.add(match.newGroup().identity())) {
                    matches.add(match);
                }
            }
        }

        List<IdentityCluster> clusters = new ArrayList<>(linkedClusters);
        for (BoundaryMatch match : matches) {
            int oldClusterIndex = clusterIndex(clusters, match.oldGroup().identity());
            int newClusterIndex = clusterIndex(clusters, match.newGroup().identity());
            if (oldClusterIndex < 0 || newClusterIndex < 0 || oldClusterIndex == newClusterIndex) continue;
            IdentityCluster oldCluster = clusters.get(oldClusterIndex);
            IdentityCluster newCluster = clusters.get(newClusterIndex);
            boolean identityConflict = oldCluster.groups().stream().anyMatch(oldGroup ->
                    newCluster.groups().stream().anyMatch(newGroup -> sharesSnapshot(oldGroup, newGroup)
                            || coexistencePairs.contains(IdentityPair.of(
                                    oldGroup.identity(), newGroup.identity()))));
            if (identityConflict) continue;
            List<IdentityGroup> mergedGroups = new ArrayList<>(oldCluster.groups());
            mergedGroups.addAll(newCluster.groups());
            int higherIndex = Math.max(oldClusterIndex, newClusterIndex);
            int lowerIndex = Math.min(oldClusterIndex, newClusterIndex);
            clusters.remove(higherIndex);
            clusters.remove(lowerIndex);
            clusters.add(new IdentityCluster(List.copyOf(mergedGroups)));
        }
        return List.copyOf(clusters);
    }

    private static boolean boundaryCompatible(IdentityGroup oldGroup, IdentityGroup newGroup,
            Parameters parameters, Set<IdentityPair> coexistencePairs) {
        if (!oldGroup.identity().sameJourney(newGroup.identity()) || sharesSnapshot(oldGroup, newGroup)
                || coexistencePairs.contains(IdentityPair.of(oldGroup.identity(), newGroup.identity()))) {
            return false;
        }
        double displacementMeters = haversineMeters(oldGroup, newGroup);
        if (!Double.isFinite(displacementMeters)
                || displacementMeters > parameters.refreshBoundaryDistanceMeters()) {
            return false;
        }
        Duration boundaryGap = Duration.between(oldGroup.maximumFetchedAt(), newGroup.minimumFetchedAt());
        if (boundaryGap.compareTo(parameters.refreshBoundaryAllowedOverlap().negated()) < 0
                || boundaryGap.compareTo(parameters.refreshBoundaryWindow()) > 0) {
            return false;
        }
        return etaProfileResidualSeconds(oldGroup, newGroup, parameters)
                <= seconds(parameters.refreshBoundaryProfileResidual());
    }

    private static int clusterIndex(List<IdentityCluster> clusters, IdentityKey identity) {
        for (int index = 0; index < clusters.size(); index++) {
            if (clusters.get(index).groups().stream().anyMatch(group -> group.identity().equals(identity))) {
                return index;
            }
        }
        return -1;
    }

    private static Map<IdentityKey, List<RoutePositionReading>> circularOccurrenceEvidence(
            List<IdentityGroup> identityGroups, double referenceLengthMeters, Parameters parameters,
            Set<IdentityPair> coexistencePairs) {
        Map<IdentityKey, List<RoutePositionReading>> evidenceByIdentity = new LinkedHashMap<>();
        identityGroups.forEach(group -> evidenceByIdentity.put(group.identity(), group.readings()));
        if (!isPositiveFinite(referenceLengthMeters)) return Map.copyOf(evidenceByIdentity);

        List<CircularLink> possibleLinks = new ArrayList<>();
        Map<IdentityKey, Integer> degree = new LinkedHashMap<>();
        for (int firstIndex = 0; firstIndex < identityGroups.size(); firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < identityGroups.size(); secondIndex++) {
                IdentityGroup first = identityGroups.get(firstIndex);
                IdentityGroup second = identityGroups.get(secondIndex);
                if (!first.identity().sameJourney(second.identity()) || sharesSnapshot(first, second)
                        || coexistencePairs.contains(IdentityPair.of(first.identity(), second.identity()))) {
                    continue;
                }
                double physicalDistance = haversineMeters(first, second);
                if (!Double.isFinite(physicalDistance)
                        || physicalDistance > parameters.circularPhaseLinkDistanceMeters()) {
                    continue;
                }
                List<RoutePositionReading> combinedEvidence = new ArrayList<>(first.readings());
                combinedEvidence.addAll(second.readings());
                ProjectionScore firstProjection = bestProjection(combinedEvidence, first.readings(),
                        referenceLengthMeters, parameters);
                ProjectionScore secondProjection = bestProjection(combinedEvidence, second.readings(),
                        referenceLengthMeters, parameters);
                if (firstProjection == null || secondProjection == null) continue;
                double rawPhaseGap = Math.abs(firstProjection.projection().referenceDistanceAlongRouteM()
                        - secondProjection.projection().referenceDistanceAlongRouteM());
                double normalizedGap = rawPhaseGap % referenceLengthMeters;
                double phaseGap = Math.min(normalizedGap, referenceLengthMeters - normalizedGap);
                if (phaseGap > parameters.circularPhaseMaximumGapMeters()
                        || Math.max(firstProjection.meanEtaErrorSeconds(), secondProjection.meanEtaErrorSeconds())
                        > seconds(parameters.circularPhaseMaximumEtaError())) {
                    continue;
                }
                possibleLinks.add(new CircularLink(first, second, physicalDistance));
                degree.merge(first.identity(), 1, Integer::sum);
                degree.merge(second.identity(), 1, Integer::sum);
            }
        }

        possibleLinks.stream()
                .filter(link -> degree.getOrDefault(link.first().identity(), 0) == 1
                        && degree.getOrDefault(link.second().identity(), 0) == 1)
                .sorted(Comparator.comparingDouble(CircularLink::physicalDistanceMeters)
                        .thenComparing(link -> link.first().identity().sortValue())
                        .thenComparing(link -> link.second().identity().sortValue()))
                .forEach(link -> {
                    List<RoutePositionReading> combinedEvidence = new ArrayList<>(link.first().readings());
                    combinedEvidence.addAll(link.second().readings());
                    List<RoutePositionReading> immutableEvidence = List.copyOf(combinedEvidence);
                    evidenceByIdentity.put(link.first().identity(), immutableEvidence);
                    evidenceByIdentity.put(link.second().identity(), immutableEvidence);
                });
        return Map.copyOf(evidenceByIdentity);
    }

    private static List<RoutePositionReading> freshestIdentityEvidence(List<RoutePositionReading> readings) {
        Map<IdentityKey, List<RoutePositionReading>> readingsByIdentity = new LinkedHashMap<>();
        readings.forEach(reading -> readingsByIdentity
                .computeIfAbsent(IdentityKey.from(reading), ignored -> new ArrayList<>())
                .add(reading));
        return readingsByIdentity.entrySet().stream()
                .max(Comparator.<Map.Entry<IdentityKey, List<RoutePositionReading>>, Instant>comparing(entry ->
                                entry.getValue().stream()
                                        .map(RoutePositionReading::fetchedAt)
                                        .max(Comparator.naturalOrder())
                                        .orElse(Instant.MIN))
                        .thenComparing(entry -> entry.getKey().sortValue()))
                .map(Map.Entry::getValue)
                .map(List::copyOf)
                .orElse(List.of());
    }

    private static boolean sharesSnapshot(IdentityGroup first, IdentityGroup second) {
        return first.snapshots().stream().anyMatch(second.snapshots()::contains);
    }

    private static double etaProfileResidualSeconds(IdentityGroup modelGroup, IdentityGroup candidateGroup,
            Parameters parameters) {
        List<EtaPoint> model = modelGroup.readings().stream()
                .map(RoutePositionPolicy::etaPoint)
                .filter(Objects::nonNull)
                .toList();
        if (model.size() < 3 || model.stream().map(EtaPoint::stopDistanceMeters).distinct().count() < 3) {
            return Double.POSITIVE_INFINITY;
        }
        double meanDistance = model.stream().mapToDouble(EtaPoint::stopDistanceMeters).average().orElseThrow();
        double meanEta = model.stream().mapToDouble(EtaPoint::etaEpochSeconds).average().orElseThrow();
        double denominator = model.stream()
                .mapToDouble(point -> Math.pow(point.stopDistanceMeters() - meanDistance, 2))
                .sum();
        if (!(denominator > 0)) return Double.POSITIVE_INFINITY;
        double slope = model.stream()
                .mapToDouble(point -> (point.stopDistanceMeters() - meanDistance)
                        * (point.etaEpochSeconds() - meanEta))
                .sum() / denominator;
        if (!(slope > parameters.refreshBoundaryProfileMinimumSecondsPerMeter()
                && slope < parameters.refreshBoundaryProfileMaximumSecondsPerMeter())) {
            return Double.POSITIVE_INFINITY;
        }
        double intercept = meanEta - slope * meanDistance;
        List<Double> residuals = candidateGroup.readings().stream()
                .map(RoutePositionPolicy::etaPoint)
                .filter(Objects::nonNull)
                .map(point -> Math.abs(point.etaEpochSeconds()
                        - (intercept + slope * point.stopDistanceMeters())))
                .toList();
        return median(residuals);
    }

    private static EtaPoint etaPoint(RoutePositionReading reading) {
        double referenceStopDistance = referenceStopDistance(reading);
        if (reading.estimatedArrivalAt() == null || !Double.isFinite(referenceStopDistance)) return null;
        return new EtaPoint(referenceStopDistance, epochSeconds(reading.estimatedArrivalAt()));
    }

    private static double etaInterceptSeconds(RoutePositionReading reading, Parameters parameters) {
        double referenceStopDistance = referenceStopDistance(reading);
        if (reading.estimatedArrivalAt() == null || !Double.isFinite(referenceStopDistance)) {
            return Double.NaN;
        }
        return epochSeconds(reading.estimatedArrivalAt())
                - referenceStopDistance / parameters.identityLinkProjectionSpeedMetersPerSecond();
    }

    private static double referenceStopDistance(RoutePositionReading reading) {
        return reading.stopReferenceDistancesM().stream()
                .filter(Double::isFinite)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(reading.stopDistanceAlongRouteM());
    }

    private static double epochSeconds(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(Double::isFinite).sorted().toList();
        if (sorted.isEmpty()) return Double.NaN;
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
    }

    private static double seconds(Duration duration) {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static double haversineMeters(IdentityGroup first, IdentityGroup second) {
        if (!Double.isFinite(first.latitude()) || !Double.isFinite(first.longitude())
                || !Double.isFinite(second.latitude()) || !Double.isFinite(second.longitude())) {
            return Double.POSITIVE_INFINITY;
        }
        double latitudeDelta = Math.toRadians(second.latitude() - first.latitude());
        double longitudeDelta = Math.toRadians(second.longitude() - first.longitude());
        double firstLatitude = Math.toRadians(first.latitude());
        double secondLatitude = Math.toRadians(second.latitude());
        double root = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.pow(Math.sin(longitudeDelta / 2), 2);
        root = Math.max(0, Math.min(1, root));
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(root), Math.sqrt(1 - root));
    }

    private static List<List<RoutePositionReading>> preserveSnapshotMultiplicity(List<RoutePositionReading> readings) {
        Map<UUID, List<RoutePositionReading>> readingsBySnapshot = new LinkedHashMap<>();
        readings.forEach(reading -> readingsBySnapshot
                .computeIfAbsent(reading.snapshotId(), ignored -> new ArrayList<>())
                .add(reading));
        Comparator<RoutePositionReading> busOrder = Comparator
                .comparing(RoutePositionReading::estimatedArrivalAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(RoutePositionReading::visitOrder)
                .thenComparing(RoutePositionReading::observationId);
        readingsBySnapshot.values().forEach(snapshot -> snapshot.sort(busOrder));
        int multiplicity = readingsBySnapshot.values().stream().mapToInt(List::size).max().orElse(0);
        List<List<RoutePositionReading>> buses = new ArrayList<>(multiplicity);
        for (int slot = 0; slot < multiplicity; slot++) {
            List<RoutePositionReading> evidence = new ArrayList<>();
            for (List<RoutePositionReading> snapshot : readingsBySnapshot.values()) {
                if (slot < snapshot.size()) evidence.add(snapshot.get(slot));
            }
            buses.add(List.copyOf(evidence));
        }
        return List.copyOf(buses);
    }

    private static double selectDistance(List<RoutePositionReading> evidenceReadings,
            List<RoutePositionReading> candidateReadings, double routeLengthMeters,
            double referenceLengthMeters, Parameters parameters) {
        double fallback = candidateReadings.stream()
                .mapToDouble(RoutePositionReading::distanceAlongRouteM)
                .average()
                .orElse(0);
        if (!isPositiveFinite(referenceLengthMeters)) {
            return clamp(fallback, routeLengthMeters);
        }
        ProjectionScore score = bestProjection(evidenceReadings, candidateReadings, referenceLengthMeters,
                parameters);
        return score == null
                ? clamp(fallback, routeLengthMeters)
                : clamp(score.projection().distanceAlongRouteM(), routeLengthMeters);
    }

    private static ProjectionScore bestProjection(List<RoutePositionReading> evidenceReadings,
            List<RoutePositionReading> candidateReadings, double referenceLengthMeters, Parameters parameters) {
        if (!isPositiveFinite(referenceLengthMeters)
                || distinctUsableStopCount(evidenceReadings)
                < parameters.circularProjectionMinimumStopCount()) {
            return null;
        }

        List<RoutePositionProjection> projections = candidateReadings.stream()
                .flatMap(reading -> reading.projectionCandidates().stream())
                .filter(RoutePositionPolicy::isUsableProjection)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (projections.isEmpty()) return null;
        double nearestOffset = projections.stream().mapToDouble(RoutePositionProjection::offsetM).min().orElse(0);

        return projections.stream()
                .filter(projection -> projection.offsetM()
                        <= nearestOffset + parameters.clusterToleranceMeters())
                .map(projection -> scoreProjection(projection, evidenceReadings, referenceLengthMeters, parameters))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(ProjectionScore::meanEtaErrorSeconds)
                        .thenComparingDouble(score -> score.projection().offsetM())
                        .thenComparingInt(score -> score.projection().segmentEndSequence())
                        .thenComparingDouble(score -> score.projection().distanceAlongRouteM()))
                .orElse(null);
    }

    private static int distinctUsableStopCount(List<RoutePositionReading> readings) {
        return (int) readings.stream()
                .filter(reading -> reading.estimatedArrivalAt() != null
                        && !reading.estimatedArrivalAt().isBefore(reading.fetchedAt())
                        && reading.stopReferenceDistancesM().stream().anyMatch(Double::isFinite))
                .map(RoutePositionReading::stopCode)
                .distinct()
                .count();
    }

    private static ProjectionScore scoreProjection(RoutePositionProjection projection,
            List<RoutePositionReading> readings, double referenceLengthMeters, Parameters parameters) {
        Map<String, List<Double>> errorsByStop = new LinkedHashMap<>();
        for (RoutePositionReading reading : readings) {
            if (reading.estimatedArrivalAt() == null || reading.estimatedArrivalAt().isBefore(reading.fetchedAt())) {
                continue;
            }
            double actualSeconds = Duration.between(reading.fetchedAt(), reading.estimatedArrivalAt()).toMillis()
                    / 1000.0;
            double smallestError = reading.stopReferenceDistancesM().stream()
                    .filter(Double::isFinite)
                    .mapToDouble(targetDistance -> {
                        double forwardDistance = targetDistance - projection.referenceDistanceAlongRouteM();
                        if (forwardDistance < 0) forwardDistance += referenceLengthMeters;
                        double predictedSeconds = forwardDistance
                                / parameters.circularProjectionSpeedMetersPerSecond();
                        return Math.abs(actualSeconds - predictedSeconds);
                    })
                    .min()
                    .orElse(Double.NaN);
            if (Double.isFinite(smallestError)) {
                errorsByStop.computeIfAbsent(reading.stopCode(), ignored -> new ArrayList<>()).add(smallestError);
            }
        }
        if (errorsByStop.size() < parameters.circularProjectionMinimumStopCount()) return null;
        double meanError = errorsByStop.values().stream()
                .mapToDouble(errors -> errors.stream().mapToDouble(Double::doubleValue).average().orElseThrow())
                .average()
                .orElseThrow();
        return new ProjectionScore(projection, meanError);
    }

    private static boolean isUsableProjection(RoutePositionProjection projection) {
        return projection != null && Double.isFinite(projection.distanceAlongRouteM())
                && Double.isFinite(projection.referenceDistanceAlongRouteM())
                && Double.isFinite(projection.offsetM()) && projection.offsetM() >= 0;
    }

    private static double clamp(double distance, double routeLength) {
        return Math.max(0, Math.min(distance, routeLength));
    }

    private static boolean isPositiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    public record Parameters(Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
                             double speedCapMetersPerSecond, double uncertaintyMeters,
                             double circularProjectionSpeedMetersPerSecond,
                             int circularProjectionMinimumStopCount,
                             double identityLinkDistanceMeters,
                             double identityLinkProjectionSpeedMetersPerSecond,
                             Duration identityEtaTolerance,
                             double refreshBoundaryDistanceMeters,
                             Duration refreshBoundaryWindow,
                             Duration refreshBoundaryAllowedOverlap,
                             Duration refreshBoundaryProfileResidual,
                             double refreshBoundaryProfileMinimumSecondsPerMeter,
                             double refreshBoundaryProfileMaximumSecondsPerMeter,
                             double circularPhaseLinkDistanceMeters,
                             double circularPhaseMaximumGapMeters,
                             Duration circularPhaseMaximumEtaError) {
        public Parameters(Duration pollInterval, int maxPollAgeCycles, double clusterToleranceMeters,
                double speedCapMetersPerSecond, double uncertaintyMeters,
                double circularProjectionSpeedMetersPerSecond, int circularProjectionMinimumStopCount) {
            this(pollInterval, maxPollAgeCycles, clusterToleranceMeters, speedCapMetersPerSecond,
                    uncertaintyMeters, circularProjectionSpeedMetersPerSecond,
                    circularProjectionMinimumStopCount, DEFAULT_IDENTITY_LINK_DISTANCE_METERS,
                    DEFAULT_IDENTITY_LINK_PROJECTION_SPEED_METERS_PER_SECOND,
                    DEFAULT_IDENTITY_ETA_TOLERANCE, DEFAULT_REFRESH_BOUNDARY_DISTANCE_METERS,
                    DEFAULT_REFRESH_BOUNDARY_WINDOW, DEFAULT_REFRESH_BOUNDARY_ALLOWED_OVERLAP,
                    DEFAULT_REFRESH_BOUNDARY_PROFILE_RESIDUAL,
                    DEFAULT_REFRESH_BOUNDARY_PROFILE_MIN_SECONDS_PER_METER,
                    DEFAULT_REFRESH_BOUNDARY_PROFILE_MAX_SECONDS_PER_METER,
                    DEFAULT_CIRCULAR_PHASE_LINK_DISTANCE_METERS,
                    DEFAULT_CIRCULAR_PHASE_MAXIMUM_GAP_METERS, DEFAULT_CIRCULAR_PHASE_MAXIMUM_ETA_ERROR);
        }

        public Parameters {
            Objects.requireNonNull(pollInterval);
            Objects.requireNonNull(identityEtaTolerance);
            Objects.requireNonNull(refreshBoundaryWindow);
            Objects.requireNonNull(refreshBoundaryAllowedOverlap);
            Objects.requireNonNull(refreshBoundaryProfileResidual);
            Objects.requireNonNull(circularPhaseMaximumEtaError);
            if (pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
            if (maxPollAgeCycles < 1) throw new IllegalArgumentException("maxPollAgeCycles must be positive");
            if (!isPositiveFinite(clusterToleranceMeters)) {
                throw new IllegalArgumentException("clusterToleranceMeters must be positive and finite");
            }
            if (!isPositiveFinite(speedCapMetersPerSecond)) {
                throw new IllegalArgumentException("speedCapMetersPerSecond must be positive and finite");
            }
            if (!isPositiveFinite(uncertaintyMeters)) {
                throw new IllegalArgumentException("uncertaintyMeters must be positive and finite");
            }
            if (!isPositiveFinite(circularProjectionSpeedMetersPerSecond)) {
                throw new IllegalArgumentException("circularProjectionSpeedMetersPerSecond must be positive and finite");
            }
            if (circularProjectionMinimumStopCount < 2) {
                throw new IllegalArgumentException("circularProjectionMinimumStopCount must be at least two");
            }
            if (!isPositiveFinite(identityLinkDistanceMeters)) {
                throw new IllegalArgumentException("identityLinkDistanceMeters must be positive and finite");
            }
            if (!isPositiveFinite(identityLinkProjectionSpeedMetersPerSecond)) {
                throw new IllegalArgumentException(
                        "identityLinkProjectionSpeedMetersPerSecond must be positive and finite");
            }
            if (identityEtaTolerance.isNegative()) {
                throw new IllegalArgumentException("identityEtaTolerance must not be negative");
            }
            if (!isPositiveFinite(refreshBoundaryDistanceMeters)) {
                throw new IllegalArgumentException("refreshBoundaryDistanceMeters must be positive and finite");
            }
            if (refreshBoundaryWindow.isNegative()) {
                throw new IllegalArgumentException("refreshBoundaryWindow must not be negative");
            }
            if (refreshBoundaryAllowedOverlap.isNegative()) {
                throw new IllegalArgumentException("refreshBoundaryAllowedOverlap must not be negative");
            }
            if (refreshBoundaryProfileResidual.isNegative()) {
                throw new IllegalArgumentException("refreshBoundaryProfileResidual must not be negative");
            }
            if (!isPositiveFinite(refreshBoundaryProfileMinimumSecondsPerMeter)) {
                throw new IllegalArgumentException(
                        "refreshBoundaryProfileMinimumSecondsPerMeter must be positive and finite");
            }
            if (!isPositiveFinite(refreshBoundaryProfileMaximumSecondsPerMeter)
                    || refreshBoundaryProfileMaximumSecondsPerMeter
                    <= refreshBoundaryProfileMinimumSecondsPerMeter) {
                throw new IllegalArgumentException(
                        "refreshBoundaryProfileMaximumSecondsPerMeter must be finite and greater than the minimum");
            }
            if (!isPositiveFinite(circularPhaseLinkDistanceMeters)) {
                throw new IllegalArgumentException("circularPhaseLinkDistanceMeters must be positive and finite");
            }
            if (!isPositiveFinite(circularPhaseMaximumGapMeters)) {
                throw new IllegalArgumentException("circularPhaseMaximumGapMeters must be positive and finite");
            }
            if (circularPhaseMaximumEtaError.isNegative()) {
                throw new IllegalArgumentException("circularPhaseMaximumEtaError must not be negative");
            }
        }
    }

    public record RoutePositionEstimate(List<RoutePositionBus> buses, int suppressedBusCount) {
        public RoutePositionEstimate {
            buses = List.copyOf(buses);
        }
    }

    private record ProjectionScore(RoutePositionProjection projection, double meanEtaErrorSeconds) { }

    private record EstimatedBus(double distanceAlongRouteM, double uncertaintyM,
                                List<UUID> evidenceObservationIds, List<UUID> positionEvidenceObservationIds,
                                int supportingStopCount,
                                IdentityKey identity) { }

    private record IdentityGroup(IdentityKey identity, List<RoutePositionReading> readings,
                                 Set<UUID> snapshots, Instant minimumFetchedAt, Instant maximumFetchedAt,
                                 double etaInterceptSeconds, double latitude, double longitude) {
        private static IdentityGroup from(IdentityKey identity, List<RoutePositionReading> sourceReadings,
                Parameters parameters) {
            List<RoutePositionReading> readings = sourceReadings.stream()
                    .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                            .thenComparing(RoutePositionReading::snapshotId)
                            .thenComparingInt(RoutePositionReading::visitOrder)
                            .thenComparing(RoutePositionReading::observationId))
                    .toList();
            Set<UUID> snapshots = readings.stream()
                    .map(RoutePositionReading::snapshotId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Instant minimumFetchedAt = readings.stream()
                    .map(RoutePositionReading::fetchedAt)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            Instant maximumFetchedAt = readings.stream()
                    .map(RoutePositionReading::fetchedAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            double etaInterceptSeconds = median(readings.stream()
                    .map(reading -> RoutePositionPolicy.etaInterceptSeconds(reading, parameters))
                    .toList());
            RoutePositionReading coordinate = readings.getFirst();
            return new IdentityGroup(identity, readings, snapshots, minimumFetchedAt, maximumFetchedAt,
                    etaInterceptSeconds, coordinate.latitude(), coordinate.longitude());
        }
    }

    private record IdentityCluster(List<IdentityGroup> groups) {
        private IdentityCluster {
            groups = List.copyOf(groups);
        }

        private List<RoutePositionReading> readings() {
            return groups.stream()
                    .flatMap(group -> group.readings().stream())
                    .sorted(Comparator.comparing(RoutePositionReading::fetchedAt)
                            .thenComparing(RoutePositionReading::snapshotId)
                            .thenComparingInt(RoutePositionReading::visitOrder)
                            .thenComparing(RoutePositionReading::observationId))
                    .toList();
        }

        private IdentityKey sortIdentity() {
            return groups.stream()
                    .map(IdentityGroup::identity)
                    .min(Comparator.comparing(IdentityKey::sortValue))
                    .orElseThrow();
        }
    }

    private record ClusterDistance(double minimumMeters, double maximumMeters) { }

    private record ClusterPair(int firstIndex, int secondIndex, ClusterDistance distance) {
        private boolean isBefore(ClusterPair other) {
            int minimumComparison = Double.compare(distance.minimumMeters(), other.distance().minimumMeters());
            if (minimumComparison != 0) return minimumComparison < 0;
            int maximumComparison = Double.compare(distance.maximumMeters(), other.distance().maximumMeters());
            if (maximumComparison != 0) return maximumComparison < 0;
            if (firstIndex != other.firstIndex()) return firstIndex < other.firstIndex();
            return secondIndex < other.secondIndex();
        }
    }

    private record BoundaryMatch(IdentityGroup oldGroup, IdentityGroup newGroup) { }

    private record CircularLink(IdentityGroup first, IdentityGroup second, double physicalDistanceMeters) { }

    private record EtaPoint(double stopDistanceMeters, double etaEpochSeconds) { }

    private record CycleSnapshot(UUID pollCycleId, UUID snapshotId) { }

    private record IdentityPair(IdentityKey first, IdentityKey second) {
        private static IdentityPair of(IdentityKey first, IdentityKey second) {
            return first.sortValue().compareTo(second.sortValue()) <= 0
                    ? new IdentityPair(first, second)
                    : new IdentityPair(second, first);
        }
    }

    private record IdentityKey(long latitudeBits, long longitudeBits, String originCode,
                               String destinationCode, int visitNumber, UUID fallbackObservationId) {
        private static IdentityKey from(RoutePositionReading reading) {
            if (!Double.isFinite(reading.latitude()) || !Double.isFinite(reading.longitude())) {
                return new IdentityKey(0, 0, normalize(reading.originCode()), normalize(reading.destinationCode()),
                        Math.max(1, reading.visitNumber()), reading.observationId());
            }
            return new IdentityKey(bits(reading.latitude()), bits(reading.longitude()),
                    normalize(reading.originCode()), normalize(reading.destinationCode()),
                    Math.max(1, reading.visitNumber()), null);
        }

        private static long bits(double value) {
            return Double.doubleToLongBits(value == 0 ? 0 : value);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        }

        private boolean sameJourney(IdentityKey other) {
            return originCode.equals(other.originCode)
                    && destinationCode.equals(other.destinationCode)
                    && visitNumber == other.visitNumber;
        }

        private String sortValue() {
            return latitudeBits + ":" + longitudeBits + ":" + originCode + ":" + destinationCode + ":"
                    + visitNumber + ":" + (fallbackObservationId == null ? "" : fallbackObservationId);
        }
    }
}
