package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRoutePositionService {
    private static final double CRUISING_SPEED_MPS = 8.33;
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final ReferenceTopologyPort topology;
    private final Clock clock;

    public DemoRoutePositionService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            ReferenceTopologyPort topology, Clock clock) {
        this.runs = runs;
        this.assignments = assignments;
        this.topology = topology;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RoutePositionSnapshot positions(UUID runId) {
        DemoRun run = runs.findById(runId).orElseThrow(() -> new DemoRunNotFoundException(runId));
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(run.status()))
            throw new IllegalArgumentException("Simulation route positions are unavailable for this run");
        DemoRunConfiguration configuration = run.configuration();
        var version = topology.activeVersion().orElseThrow(() -> new ReferenceTopologyUnavailableException());
        var route = topology.busRoute(version.id(), configuration.serviceId())
                .orElseThrow(() -> new IllegalArgumentException("Selected bus service is unavailable"));
        var operator = route.operators().stream()
                .filter(value -> value.directions().stream().anyMatch(direction -> direction.directionId() == configuration.directionId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected route direction is unavailable"));
        var direction = operator.directions().stream().filter(value -> value.directionId() == configuration.directionId())
                .findFirst().orElseThrow();
        double routeLength = routeLength(direction.stops());
        List<DemoVehicleAssignment> fleet = assignments.findByIdDemoRunIdOrderByBusId(runId,
                PageRequest.of(0, configuration.busCount())).getContent();
        java.time.Instant now = clock.instant();
        java.time.Instant referenceTime = run.completedAt() != null ? run.completedAt() : now;
        double selectedStopDistance = distanceToStop(direction.stops(), configuration.monitoredStopCode());
        double elapsedSeconds = Math.min(60.0, java.time.Duration.between(run.startedAt(), referenceTime).toSeconds());
        double leadDistance = Math.max(0, selectedStopDistance - 30) + CRUISING_SPEED_MPS * elapsedSeconds;
        List<RoutePositionBus> buses = java.util.stream.IntStream.range(0, fleet.size()).mapToObj(index -> {
            double precedingGaps = configuration.busGaps().subList(0, index).stream()
                    .mapToLong(java.time.Duration::toSeconds).sum();
            double distance = leadDistance - CRUISING_SPEED_MPS * precedingGaps;
            return new RoutePositionBus(index + 1, Math.max(0, Math.min(routeLength, distance)), 20, CRUISING_SPEED_MPS);
        }).toList();
        return new RoutePositionSnapshot(configuration.serviceId(), now, java.util.Optional.empty(),
                java.util.Optional.of(now), 1, 86400,
                List.of(new RoutePositionDirection(operator.operator(), configuration.directionId(), routeLength, buses)));
    }

    private double routeLength(List<ReferenceTopologyPort.BusRouteStop> stops) {
        if (stops.size() < 2) return 1;
        double distance = 0;
        for (int index = 1; index < stops.size(); index++) distance += distance(stops.get(index - 1), stops.get(index));
        return Math.max(1, distance);
    }

    private double distanceToStop(List<ReferenceTopologyPort.BusRouteStop> stops, String stopCode) {
        double distance = 0;
        for (int index = 0; index < stops.size(); index++) {
            if (stopCode.equals(stops.get(index).stopCode())) return distance;
            if (index + 1 < stops.size()) distance += distance(stops.get(index), stops.get(index + 1));
        }
        throw new IllegalArgumentException("Selected stop is unavailable on this route direction");
    }

    private double distance(ReferenceTopologyPort.BusRouteStop first, ReferenceTopologyPort.BusRouteStop second) {
        BigDecimal firstLatitude = first.latitude(); BigDecimal firstLongitude = first.longitude();
        BigDecimal secondLatitude = second.latitude(); BigDecimal secondLongitude = second.longitude();
        if (firstLatitude == null || firstLongitude == null || secondLatitude == null || secondLongitude == null) return 1000;
        double latitude = Math.toRadians(secondLatitude.doubleValue() - firstLatitude.doubleValue());
        double longitude = Math.toRadians(secondLongitude.doubleValue() - firstLongitude.doubleValue());
        double a = Math.sin(latitude / 2) * Math.sin(latitude / 2)
                + Math.cos(Math.toRadians(firstLatitude.doubleValue())) * Math.cos(Math.toRadians(secondLatitude.doubleValue()))
                * Math.sin(longitude / 2) * Math.sin(longitude / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
