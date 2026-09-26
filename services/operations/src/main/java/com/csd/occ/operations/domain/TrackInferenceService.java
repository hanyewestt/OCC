package com.csd.occ.operations.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackInferenceService {
    private final LiveMapPort live;
    private final RouteReferencePort routes;
    private final InferredVehicleTrackRepository tracks;
    private final InferredObservationLinkRepository links;
    private final TrackInferenceSettings settings;
    private final TrackInferencePolicy policy;
    private final Clock clock;
    public TrackInferenceService(LiveMapPort live, RouteReferencePort routes, InferredVehicleTrackRepository tracks,
            InferredObservationLinkRepository links, TrackInferenceSettings settings, Clock clock) {
        this.live=live; this.routes=routes; this.tracks=tracks; this.links=links; this.settings=settings;
        this.clock=clock; this.policy=new TrackInferencePolicy(settings);
    }

    @Transactional
    public void refresh() {
        var now = Instant.now(clock);
        // PostgreSQL transaction lock also serializes multiple Operations instances.
        if (!tracks.lockProjection()) return;
        links.deleteByFetchedAtBefore(now.minus(settings.retention()));
        tracks.deleteByLastSeenAtBefore(now.minus(settings.retention()));
        var existing = tracks.findByStatusNot("EXPIRED");
        existing.stream().filter(t -> !t.getLastSeenAt().plus(settings.expiry()).isAfter(now)).forEach(InferredVehicleTrack::expire);
        var observations = new ArrayList<LiveMapPort.Observation>();
        for (int page=0; page<settings.maxPages(); page++) {
            var response = live.observations(null, page, settings.pageSize());
            observations.addAll(response.items());
            if (page + 1 >= response.totalPages()) break;
            if (page + 1 == settings.maxPages()) throw new IllegalStateException("Track source exceeds configured page limit");
        }
        var seen = new HashSet<UUID>();
        links.findByObservationIdIn(observations.stream().map(LiveMapPort.Observation::observationId).toList())
                .forEach(link -> seen.add(link.getObservationId()));
        var candidates = new ArrayList<TrackInferencePolicy.Candidate>();
        var cache = new HashMap<String, java.util.Optional<RouteReferencePort.Route>>();
        for (var observation : observations) {
            if (!seen.add(observation.observationId()) || observation.stale()
                    || observation.fetchedAt().isAfter(now) || !observation.fetchedAt().plus(settings.staleAfter()).isAfter(now)) continue;
            var route = cache.computeIfAbsent(observation.serviceId(), routes::route);
            var candidate = route.flatMap(value -> policy.project(observation, value));
            if (candidate.isPresent()) candidates.add(candidate.get());
            else links.save(new InferredObservationLink(observation, null, "UNRESOLVED_ROUTE"));
        }
        var groups = policy.cluster(candidates);
        for (var group : groups) existing.stream().filter(t -> t.getServiceId().equals(group.serviceId())
                && t.getDirectionId() == group.directionId() && t.getOperator().equals(group.operator())
                && !t.getRouteFingerprint().equals(group.routeFingerprint())).forEach(InferredVehicleTrack::expire);
        record Match(InferredVehicleTrack track, double score) { }
        var choices = new HashMap<TrackInferencePolicy.Candidate, List<Match>>();
        for (var group : groups) {
            choices.put(group, existing.stream().flatMap(track -> policy.matchScore(track, group).stream()
                    .map(score -> new Match(track, score))).sorted(Comparator.comparingDouble(Match::score)).toList());
        }
        var claimed = new HashSet<UUID>();
        for (var group : groups) {
            var matches = choices.get(group);
            InferredVehicleTrack target;
            if (matches.isEmpty()) {
                // An unresolved prior identity must not silently regain an ACTIVE label.
                boolean nearAmbiguous = existing.stream().anyMatch(t -> t.getStatus().equals("AMBIGUOUS")
                        && t.getServiceId().equals(group.serviceId()) && t.getDirectionId() == group.directionId()
                        && Math.abs(t.getProgressMeters()-group.progressMeters()) <= settings.clusterMeters());
                target = tracks.save(new InferredVehicleTrack(group));
                if (nearAmbiguous) target.ambiguous();
            } else {
                var best = matches.getFirst();
                boolean competing = matches.size()>1 && matches.get(1).score()-best.score() <= settings.ambiguityMarginMeters();
                boolean contested = groups.stream().filter(other -> !other.equals(group)).anyMatch(other -> {
                    var alternatives=choices.get(other);
                    return !alternatives.isEmpty() && alternatives.getFirst().track().getId().equals(best.track().getId());
                });
                if (competing || contested || claimed.contains(best.track().getId()) || best.track().getStatus().equals("AMBIGUOUS")) {
                    matches.stream().filter(m -> m.score()-best.score() <= settings.ambiguityMarginMeters()).forEach(m -> m.track().ambiguous());
                    for (var evidence : group.evidence()) links.save(new InferredObservationLink(evidence, null, "AMBIGUOUS_MATCH"));
                    continue;
                }
                target=best.track(); target.observe(group); claimed.add(target.getId());
            }
            for (var evidence : group.evidence()) links.save(new InferredObservationLink(evidence, target.getId(), "LINKED"));
        }
    }

    @Transactional(readOnly = true)
    public List<InferredVehicleTrack> current(String serviceId) {
        var now = Instant.now(clock);
        return tracks.findByLastSeenAtAfterOrderByServiceIdAscIdAsc(now.minus(settings.staleAfter())).stream()
                .filter(t -> !t.getStatus().equals("EXPIRED") && (serviceId == null || serviceId.isBlank() || t.getServiceId().equals(serviceId))).toList();
    }
    public List<UUID> evidence(UUID trackId) {
        return links.findByTrackId(trackId).stream().map(InferredObservationLink::getObservationId).toList();
    }
}
