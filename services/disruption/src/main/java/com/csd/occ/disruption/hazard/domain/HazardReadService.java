package com.csd.occ.disruption.hazard.domain;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class HazardReadService {
    private final HazardStateRepository hazards;
    private final ConsumerSelectionService selection;
    private final HazardTransitPort transit;
    private final Clock clock;
    private final com.csd.occ.disruption.bunching.domain.DisruptionRepository disruptions;
    private final com.csd.occ.disruption.traffic.domain.TrafficIncidentStateRepository traffic;
    private final HazardCheckpointRepository checkpoints;
    private final HazardExposurePolicy exposure = new HazardExposurePolicy();
    public HazardReadService(HazardStateRepository hazards, ConsumerSelectionService selection,
            HazardTransitPort transit, Clock clock, com.csd.occ.disruption.bunching.domain.DisruptionRepository disruptions,
            com.csd.occ.disruption.traffic.domain.TrafficIncidentStateRepository traffic, HazardCheckpointRepository checkpoints) {
        this.hazards = hazards; this.selection = selection; this.transit = transit; this.clock = clock;
        this.disruptions = disruptions; this.traffic = traffic; this.checkpoints = checkpoints;
    }
    public Page list(String kind, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid hazard pagination");
        var scope = selection.current();
        var found = disruptions.findCurrentHazardIds(kind, scope.demoRunId(), clock.instant(), PageRequest.of(page, size));
        var routes = new java.util.HashMap<String, HazardTransitPort.Route>();
        var fleets = new java.util.HashMap<UUID, HazardTransitPort.Fleet>();
        var stations = new java.util.HashMap<String, java.util.Optional<HazardTransitPort.Station>>();
        var items = found.stream().map(id -> view(snapshot(id), routes, fleets, stations)).toList();
        return new Page(items, page, size, found.getTotalElements(),
                checkpoints.findAllByOrderByKindAsc().stream().map(checkpoint -> checkpoint.health(clock.instant())).toList());
    }
    public View find(UUID disruptionId) {
        return view(snapshot(disruptionId), new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
    }
    private Snapshot snapshot(UUID id) {
        var hazard = hazards.findByDisruptionId(id);
        if (hazard.isPresent()) {
            var h = hazard.get();
            return new Snapshot(h.disruptionId(), h.kind(), h.status(), h.inactiveReason(), h.classification(), h.demoRunId(),
                    h.sourceObservationId(), h.updatedAt(), h.facts());
        }
        var foundTraffic = traffic.findByDisruptionId(id);
        if (foundTraffic.isEmpty()) {
            var d = disruptions.findById(id).orElseThrow(() -> new IllegalArgumentException("Hazard not found"));
            if (!"BUNCHING".equals(d.kind())) throw new IllegalArgumentException("Unsupported disruption kind");
            var facts = new HazardPollPort.Fact("BUNCHING", id.toString(), "OBSERVED", null, null, null,
                    "Bus bunching", "Validated synthetic bunching context", null, null, List.of(), null, null, null, null, List.of());
            return new Snapshot(id, d.kind(), d.status(), null, d.classification(), d.demoRunId(), null, d.updatedAt(), facts);
        }
        var t = foundTraffic.get();
        var location = new HazardPollPort.Location("POINT", t.sourceIncidentKey(), t.message(), t.latitude(), t.longitude(),
                null, "INCIDENT_POINT", "PROVIDER_SUPPLIED");
        var facts = new HazardPollPort.Fact("TRAFFIC_INCIDENT", t.sourceIncidentKey(), "OBSERVED", t.observedAt(), null,
                null, t.incidentType(), t.message(), null, null, List.of(), null, null, null, null, List.of(location));
        return new Snapshot(t.disruptionId(), "TRAFFIC_INCIDENT", t.isActive() ? "ACTIVE" : "CLEARED", t.inactiveReason(),
                t.classification(), t.demoRunId(), t.sourceObservationId(), t.updatedAt(), facts);
    }
    private View view(Snapshot state, java.util.Map<String, HazardTransitPort.Route> routes,
            java.util.Map<UUID, HazardTransitPort.Fleet> fleets,
            java.util.Map<String, java.util.Optional<HazardTransitPort.Station>> stations) {
        List<String> limitations = new ArrayList<>();
        List<HazardExposurePolicy.Exposure> matches = new ArrayList<>();
        List<HazardPollPort.Location> locations = new ArrayList<>();
        String status = state.status();
        if (state.facts().effectiveUntil() != null && !clock.instant().isBefore(state.facts().effectiveUntil())) status = "CLEARED";
        else if (!"CLEARED".equals(status) && state.facts().effectiveFrom() != null)
            status = clock.instant().isBefore(state.facts().effectiveFrom()) ? "SCHEDULED" : "ACTIVE";
        UUID runId = selection.current().demoRunId();
        try {
            for (var location : state.facts().locations()) {
                if ("STATION_REFERENCE".equals(location.geometryRole())) {
                    var station = stations.computeIfAbsent(location.sourceReference(), transit::station);
                    if (station.isPresent()) {
                        var value = station.get();
                        locations.add(new HazardPollPort.Location("STATION", value.code(), value.name(), value.latitude(),
                                value.longitude(), null, "STATION_PROXIMITY", "INFERRED"));
                    } else limitations.add("Unresolved station: " + location.sourceReference());
                } else locations.add(location);
            }
            if (runId != null && (state.demoRunId() == null || runId.equals(state.demoRunId()))) {
                var fleet = fleets.computeIfAbsent(runId, id -> transit.fleet(id, 0));
                if (fleet.items().size() < fleet.totalElements()) limitations.add("Only the first fleet page was assessed.");
                for (var bus : fleet.items()) {
                    if (bus.progress() == null) { limitations.add("Progress unavailable for " + bus.busId()); continue; }
                    matches.addAll(exposure.assess(bus, routes.computeIfAbsent(bus.serviceId(), transit::route), locations, transit.associationRadiusKm()));
                }
            } else limitations.add("No active synthetic fleet; live arrival predictions do not identify buses.");
        } catch (RuntimeException unavailable) { limitations.add("Transit reference or simulation context unavailable; exposure is incomplete."); }
        limitations.add("Candidate associations do not establish causation, road closure, safe speed or available capacity.");
        return new View(state.disruptionId(), state.kind(), status, state.inactiveReason(), state.classification(),
                state.demoRunId(), state.sourceObservationId(), state.updatedAt(), state.facts(), matches, limitations);
    }
    public record Page(List<View> items, int page, int size, long totalElements, List<HazardCheckpoint.FeedHealth> feeds) {}
    private record Snapshot(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                       UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardPollPort.Fact facts) {}
    public record View(UUID disruptionId, String kind, String status, String inactiveReason, String classification,
                       UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardPollPort.Fact facts,
                       List<HazardExposurePolicy.Exposure> exposures, List<String> limitations) {}
}
