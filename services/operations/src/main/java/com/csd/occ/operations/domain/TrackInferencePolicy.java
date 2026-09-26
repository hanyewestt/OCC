package com.csd.occ.operations.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Conservative route-stop projection. Stop-to-stop geometry is an approximation, not a road path. */
public class TrackInferencePolicy {
    private final TrackInferenceSettings settings;
    public TrackInferencePolicy(TrackInferenceSettings settings) { this.settings = settings; }

    public record Candidate(String serviceId, int directionId, String operator, String routeFingerprint, double progressMeters,
            LiveMapPort.Observation latest, List<LiveMapPort.Observation> evidence) { }
    private record Projection(RouteReferencePort.Direction direction, double progress, double offset) { }

    public Optional<Candidate> project(LiveMapPort.Observation observation, RouteReferencePort.Route route) {
        if (observation.latitude() == null || observation.longitude() == null) return Optional.empty();
        var eligible = route.directions().stream().filter(direction ->
                (observation.directionId() == null || direction.directionId() == observation.directionId())
                && direction.stops().stream().filter(stop -> stop.stopCode().equals(observation.stopCode())).count() == 1).toList();
        // DataMall often omits direction. Infer it only from unique stop membership, never arrival ordering.
        if (eligible.size() != 1) return Optional.empty();
        var projections = new ArrayList<Projection>();
        for (var direction : eligible) {
            // A stop must have a unique visit in this direction; repeated visits need additional evidence.
            if (direction.stops().stream().filter(stop -> stop.stopCode().equals(observation.stopCode())).count() != 1) continue;
            var stops = direction.stops().stream().sorted(Comparator.comparingInt(RouteReferencePort.Stop::stopSequence)).toList();
            double distance = 0;
            for (int i = 1; i < stops.size(); i++) {
                var a = stops.get(i - 1); var b = stops.get(i);
                if (a.latitude() == null || a.longitude() == null || b.latitude() == null || b.longitude() == null) continue;
                double length = meters(a.latitude().doubleValue(), a.longitude().doubleValue(), b.latitude().doubleValue(), b.longitude().doubleValue());
                double x = (b.longitude().doubleValue() - a.longitude().doubleValue()) * Math.cos(Math.toRadians(a.latitude().doubleValue()));
                double y = b.latitude().doubleValue() - a.latitude().doubleValue();
                double px = (observation.longitude().doubleValue() - a.longitude().doubleValue()) * Math.cos(Math.toRadians(a.latitude().doubleValue()));
                double py = observation.latitude().doubleValue() - a.latitude().doubleValue();
                double fraction = x*x + y*y == 0 ? 0 : Math.max(0, Math.min(1, (px*x + py*y) / (x*x + y*y)));
                double lat = a.latitude().doubleValue() + fraction * y;
                double lon = a.longitude().doubleValue() + fraction * (b.longitude().doubleValue() - a.longitude().doubleValue());
                projections.add(new Projection(direction, distance + fraction * length,
                        meters(lat, lon, observation.latitude().doubleValue(), observation.longitude().doubleValue())));
                distance += length;
            }
        }
        projections.sort(Comparator.comparingDouble(Projection::offset));
        if (projections.isEmpty() || projections.getFirst().offset() > settings.maxRouteOffsetMeters()) return Optional.empty();
        var best = projections.getFirst();
        // Reject overlapping operators and route self-intersections with incompatible progress.
        if (projections.stream().skip(1).anyMatch(p -> p.offset() - best.offset() < settings.ambiguityMarginMeters()
                && (!p.direction().operator().equals(best.direction().operator())
                || Math.abs(p.progress() - best.progress()) > settings.clusterMeters()))) return Optional.empty();
        return Optional.of(new Candidate(observation.serviceId(), best.direction().directionId(), best.direction().operator(),
                java.util.UUID.nameUUIDFromBytes(best.direction().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                best.progress(), observation, List.of(observation)));
    }

    public List<Candidate> cluster(List<Candidate> input) {
        var groups = new ArrayList<Candidate>();
        for (var candidate : input.stream().sorted(Comparator.comparing((Candidate c) -> c.latest().fetchedAt())
                .thenComparing(c -> c.latest().observationId())).toList()) {
            var compatible = groups.stream().filter(group -> sameRoute(group, candidate)
                    && Math.abs(group.progressMeters() - candidate.progressMeters()) <= settings.clusterMeters()
                    && group.evidence().stream().allMatch(item -> !item.stopCode().equals(candidate.latest().stopCode())
                        && Duration.between(item.fetchedAt(), candidate.latest().fetchedAt()).abs().compareTo(settings.clusterTimeWindow()) <= 0
                        && meters(item.latitude().doubleValue(), item.longitude().doubleValue(), candidate.latest().latitude().doubleValue(), candidate.latest().longitude().doubleValue()) <= settings.clusterMeters())).toList();
            if (compatible.size() != 1) { groups.add(candidate); continue; }
            var group = compatible.getFirst();
            var evidence = new ArrayList<>(group.evidence()); evidence.addAll(candidate.evidence());
            groups.set(groups.indexOf(group), new Candidate(candidate.serviceId(), candidate.directionId(), candidate.operator(),
                    candidate.routeFingerprint(),
                    candidate.progressMeters(), candidate.latest(), List.copyOf(evidence)));
        }
        return groups;
    }

    private boolean sameRoute(Candidate a, Candidate b) {
        return a.serviceId().equals(b.serviceId()) && a.directionId() == b.directionId() && a.operator().equals(b.operator())
                && a.routeFingerprint().equals(b.routeFingerprint());
    }

    public Optional<Double> matchScore(InferredVehicleTrack track, Candidate candidate) {
        if (!track.getServiceId().equals(candidate.serviceId()) || track.getDirectionId() != candidate.directionId()
                || !track.getOperator().equals(candidate.operator()) || track.getStatus().equals("AMBIGUOUS") || track.getStatus().equals("EXPIRED")) return Optional.empty();
        if (!track.getRouteFingerprint().equals(candidate.routeFingerprint())) return Optional.empty();
        double seconds = Duration.between(track.getLastSeenAt(), candidate.latest().fetchedAt()).toMillis() / 1000.0;
        if (seconds < -settings.clusterTimeWindow().toSeconds() || seconds > settings.expiry().toSeconds()) return Optional.empty();
        double movement = candidate.progressMeters() - track.getProgressMeters();
        double allowance = settings.clusterMeters() + Math.max(0, seconds) * settings.maxSpeedMetersPerSecond();
        if (movement < -settings.backwardToleranceMeters() || movement > allowance
                || meters(track.getLatitude(), track.getLongitude(), candidate.latest().latitude().doubleValue(), candidate.latest().longitude().doubleValue()) > allowance) return Optional.empty();
        if (track.getAnchorStop().equals(candidate.latest().stopCode()) && track.getAnchorEta() != null
                && candidate.latest().estimatedArrivalAt() != null
                && Duration.between(track.getAnchorEta(), candidate.latest().estimatedArrivalAt()).abs().compareTo(settings.etaTolerance()) > 0) return Optional.empty();
        return Optional.of(Math.abs(movement));
    }

    public Optional<Double> matchScore(InferredVehicleTrack track, Candidate candidate, List<InferredObservationLink> history) {
        var movementScore = matchScore(track, candidate);
        if (movementScore.isEmpty()) return movementScore;
        // Compare each common stop with its most recent linked ETA, not arrival-slot order.
        var latest = new java.util.HashMap<String, InferredObservationLink>();
        history.stream().filter(link -> link.getStopCode()!=null && link.getEstimatedArrivalAt()!=null)
                .forEach(link -> latest.merge(link.getStopCode(), link,
                        (a,b) -> a.getFetchedAt().isAfter(b.getFetchedAt()) ? a : b));
        var differences = new ArrayList<Double>();
        for (var observation : candidate.evidence()) {
            var previous = latest.get(observation.stopCode());
            if (previous == null || observation.estimatedArrivalAt() == null) continue;
            double seconds = Duration.between(previous.getEstimatedArrivalAt(), observation.estimatedArrivalAt()).abs().toMillis()/1000.0;
            if (seconds > settings.etaTolerance().toMillis()/1000.0) return Optional.empty();
            differences.add(seconds);
        }
        if (differences.isEmpty()) return movementScore;
        // ETA continuity dominates proximity when there is shared-stop evidence.
        return Optional.of(differences.stream().mapToDouble(Double::doubleValue).average().orElseThrow()
                * settings.maxSpeedMetersPerSecond() + movementScore.get() * .1);
    }

    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        // Mean Earth radius is a physical constant, not an operational threshold.
        double a = Math.pow(Math.sin(Math.toRadians(lat2-lat1)/2), 2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(Math.toRadians(lon2-lon1)/2), 2);
        return 6371008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1-a)));
    }
}
