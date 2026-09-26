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
    private final InferredTrackFrameRepository frames;
    private final TrackInferenceSettings settings;
    private final TrackInferencePolicy policy;
    private final Clock clock;
    public TrackInferenceService(LiveMapPort live, RouteReferencePort routes, InferredVehicleTrackRepository tracks,
            InferredObservationLinkRepository links, InferredTrackFrameRepository frames, TrackInferenceSettings settings, Clock clock) {
        this.live=live; this.routes=routes; this.tracks=tracks; this.links=links; this.frames=frames; this.settings=settings;
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
        var linkedTracks = new HashMap<UUID, UUID>();
        links.findByObservationIdIn(observations.stream().map(LiveMapPort.Observation::observationId).toList())
                .forEach(link -> {
                    seen.add(link.getObservationId());
                    if (link.getTrackId()!=null) linkedTracks.put(link.getObservationId(),link.getTrackId());
                });
        var latestSources = new HashMap<String, Instant>();
        observations.stream().filter(o -> !o.stale() && !o.fetchedAt().isAfter(now)
                && o.fetchedAt().plus(settings.staleAfter()).isAfter(now))
                .forEach(o -> latestSources.merge(o.serviceId(), o.fetchedAt(), (a,b) -> a.isAfter(b) ? a : b));
        var newFrames = new HashMap<String, InferredTrackFrame>();
        latestSources.forEach((service, time) -> newFrames.put(service,new InferredTrackFrame(service,time)));
        var unique = new HashSet<UUID>();
        var candidates = new ArrayList<TrackInferencePolicy.Candidate>();
        var cache = new HashMap<String, java.util.Optional<RouteReferencePort.Route>>();
        for (var observation : observations) {
            var latest = latestSources.get(observation.serviceId());
            if (!unique.add(observation.observationId()) || latest == null || observation.stale()
                    || observation.fetchedAt().isAfter(now)
                    || observation.fetchedAt().isBefore(latest.minus(settings.clusterTimeWindow()))) continue;
            var route = cache.computeIfAbsent(observation.serviceId(), routes::route);
            var candidate = route.flatMap(value -> policy.project(observation, value));
            if (candidate.isPresent()) candidates.add(candidate.get());
            else if (!seen.contains(observation.observationId())) links.save(new InferredObservationLink(observation, null, "UNRESOLVED_ROUTE"));
        }
        var groups = policy.cluster(candidates);
        for (var group : groups) existing.stream().filter(t -> t.getServiceId().equals(group.serviceId())
                && t.getDirectionId() == group.directionId() && t.getOperator().equals(group.operator())
                && !t.getRouteFingerprint().equals(group.routeFingerprint())).forEach(InferredVehicleTrack::expire);
        record Match(InferredVehicleTrack track, double score) { }
        var choices = new HashMap<TrackInferencePolicy.Candidate, List<Match>>();
        var history = new HashMap<UUID, List<InferredObservationLink>>();
        existing.forEach(track -> history.put(track.getId(), links.findByTrackId(track.getId())));
        for (var group : groups) {
            choices.put(group, existing.stream().flatMap(track -> policy.matchScore(track, group, history.get(track.getId())).stream()
                    .map(score -> new Match(track, score))).sorted(Comparator.comparingDouble(Match::score)).toList());
        }
        var claimed = new HashSet<UUID>();
        for (var group : groups.stream().sorted(Comparator.comparingDouble(g -> choices.get(g).isEmpty()
                ? Double.MAX_VALUE : choices.get(g).getFirst().score())).toList()) {
            // Re-rendering the same source evidence must never mint another identity.
            if (group.evidence().stream().allMatch(o -> seen.contains(o.observationId()))) {
                var prior = linkedTracks.get(group.latest().observationId());
                if (prior!=null) newFrames.get(group.serviceId()).getTrackIds().add(prior);
                continue;
            }
            var matches = choices.get(group).stream().filter(m -> !claimed.contains(m.track().getId())).toList();
            InferredVehicleTrack target;
            if (matches.isEmpty()) {
                target = tracks.save(new InferredVehicleTrack(group));
            } else {
                var best = matches.getFirst();
                boolean competing = matches.size()>1 && matches.get(1).score()-best.score() <= settings.ambiguityMarginMeters();
                boolean contested = groups.stream().filter(other -> !other.equals(group)).anyMatch(other -> {
                    var alternatives=choices.get(other);
                    return !alternatives.isEmpty() && alternatives.getFirst().track().getId().equals(best.track().getId());
                });
                contested = contested && groups.stream().filter(other -> !other.equals(group)
                        && other.evidence().stream().anyMatch(o -> !seen.contains(o.observationId())))
                        .anyMatch(other -> {
                    var alternatives=choices.get(other);
                    return !alternatives.isEmpty() && alternatives.getFirst().track().getId().equals(best.track().getId())
                            && Math.abs(alternatives.getFirst().score()-best.score()) <= settings.ambiguityMarginMeters();
                });
                if (competing || contested || claimed.contains(best.track().getId()) || best.track().getStatus().equals("AMBIGUOUS")) {
                    matches.stream().filter(m -> m.score()-best.score() <= settings.ambiguityMarginMeters()).forEach(m -> m.track().ambiguous());
                    target = tracks.save(new InferredVehicleTrack(group));
                    target.ambiguous();
                    newFrames.get(group.serviceId()).getTrackIds().add(target.getId());
                    for (var evidence : group.evidence()) if (!seen.contains(evidence.observationId()))
                        links.save(new InferredObservationLink(evidence, target.getId(), "AMBIGUOUS_MATCH"));
                    continue;
                }
                target=best.track(); target.observe(group); claimed.add(target.getId());
            }
            newFrames.get(group.serviceId()).getTrackIds().add(target.getId());
            for (var evidence : group.evidence()) if (!seen.contains(evidence.observationId()))
                links.save(new InferredObservationLink(evidence, target.getId(), "LINKED"));
        }
        frames.saveAll(newFrames.values());
    }

    @Transactional(readOnly = true)
    public List<InferredVehicleTrack> current(String serviceId) {
        var now = Instant.now(clock);
        var sourceTimes = new HashMap<String, Instant>();
        var displayedIds = new HashSet<UUID>();
        frames.findAll().forEach(frame -> {
            sourceTimes.put(frame.getServiceId(), frame.getSourceAt());
            displayedIds.addAll(frame.getTrackIds());
        });
        return tracks.findByLastSeenAtAfterOrderByServiceIdAscIdAsc(now.minus(settings.staleAfter())).stream()
                .filter(t -> displayedIds.contains(t.getId()) && !t.getStatus().equals("EXPIRED") && sourceTimes.containsKey(t.getServiceId())
                        && !t.getLastSeenAt().isBefore(sourceTimes.get(t.getServiceId()).minus(settings.clusterTimeWindow()))
                        && (serviceId == null || serviceId.isBlank() || t.getServiceId().equals(serviceId))).toList();
    }
    public List<UUID> evidence(UUID trackId) {
        return links.findByTrackId(trackId).stream().map(InferredObservationLink::getObservationId).toList();
    }
}
