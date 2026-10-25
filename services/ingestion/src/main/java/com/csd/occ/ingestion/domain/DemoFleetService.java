package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoFleetService {
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final BusArrivalObservationRepository arrivals;
    private final ReferenceTopologyPort topology;

    public DemoFleetService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            BusArrivalObservationRepository arrivals, ReferenceTopologyPort topology) {
        this.runs = runs; this.assignments = assignments; this.arrivals = arrivals; this.topology = topology;
    }

    @Transactional(readOnly = true)
    public DemoFleetSnapshot find(UUID runId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        DemoRun run = runs.findById(runId).orElseThrow(() -> new DemoRunNotFoundException(runId));
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(run.status()))
            return new DemoFleetSnapshot(runId, run.status(), List.of(), 0, page, size);
        var found = assignments.findByIdDemoRunIdOrderByBusId(runId, PageRequest.of(page, size));
        return new DemoFleetSnapshot(runId, run.status(), found.map(assignment -> {
            var captain = assignment.captainUserId();
            return new DemoFleetSnapshot.Vehicle(
                    assignment.busId() == null ? assignment.id().tripId() : assignment.busId(),
                    assignment.id().tripId(), assignment.vehicleRegistrationNumber(), assignment.serviceId(),
                    assignment.directionId(), captain, captain.isPresent() ? "ASSIGNED" : "CAPTAIN_ASSIGNMENT_UNAVAILABLE",
                    progress(assignment));
        }).toList(), found.getTotalElements(), page, size);
    }

    private Optional<DemoFleetSnapshot.Progress> progress(DemoVehicleAssignment assignment) {
        return arrivals.findFirstByDemoRunIdAndTripIdAndClassificationOrderByFetchedAtDescIdDesc(
                assignment.id().demoRunId(), assignment.id().tripId(), "SIMULATION").flatMap(arrival -> {
            var candidates = topology.busRoute(arrival.getReferenceVersionId(), assignment.serviceId()).stream()
                        .flatMap(route -> route.operators().stream()).flatMap(operator -> operator.directions().stream())
                        .filter(direction -> direction.directionId() == assignment.directionId())
                        .flatMap(direction -> direction.stops().stream())
                        .filter(stop -> stop.stopCode().equals(arrival.getStopCode())).distinct().toList();
            if (candidates.size() != 1) return Optional.empty();
            var stop = candidates.getFirst();
            return Optional.of(new DemoFleetSnapshot.Progress(stop.stopCode(), stop.stopSequence(),
                    arrival.getFetchedAt(), 30, List.of(5), List.of(300)));
        });
        // Speed values are labelled scenario assumptions, never measured vehicle telemetry.
    }
}
