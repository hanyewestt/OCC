package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Route view for the captain's On Board Unit: the simulation bus assigned to the signed-in captain
 * in the active demo run, its real route stops and its position along them.
 */
@Service
public class CaptainRouteService {
    private static final List<String> CURRENT_RUN_STATUSES = List.of("RUNNING", "REPLAY_COMPLETE");

    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final DemoFleetService fleet;
    private final BusArrivalObservationRepository arrivals;
    private final ReferenceTopologyQueryService topology;
    private final PollingConfigurationPort polling;
    private final Clock clock;
    private final CaptainRoutePolicy policy = new CaptainRoutePolicy();

    public CaptainRouteService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
                               DemoFleetService fleet, BusArrivalObservationRepository arrivals,
                               ReferenceTopologyQueryService topology, PollingConfigurationPort polling, Clock clock) {
        this.runs = runs;
        this.assignments = assignments;
        this.fleet = fleet;
        this.arrivals = arrivals;
        this.topology = topology;
        this.polling = polling;
        this.clock = clock;
    }

    /** Empty when the captain has no bus in the current simulation run. */
    @Transactional(readOnly = true)
    public Optional<CaptainRoute> current(String captainSubject) {
        Optional<UUID> captain = parse(captainSubject);
        if (captain.isEmpty()) return Optional.empty();
        return runs.findActive().filter(run -> CURRENT_RUN_STATUSES.contains(run.status()))
                .flatMap(run -> assignments.findByIdDemoRunIdAndCaptainUserId(run.id(), captain.get()))
                .flatMap(this::route);
    }

    private Optional<CaptainRoute> route(DemoVehicleAssignment assignment) {
        var stops = topology.busRoute(assignment.serviceId()).stream()
                .flatMap(route -> route.operators().stream())
                .flatMap(operator -> operator.directions().stream())
                .filter(direction -> direction.directionId() == assignment.directionId())
                .findFirst().map(ReferenceTopologyPort.BusRouteDirection::stops).orElse(List.of());
        if (stops.isEmpty()) return Optional.empty();
        var progress = fleet.progress(assignment).flatMap(value -> arrivals
                .findFirstByDemoRunIdAndTripIdAndClassificationOrderByFetchedAtDescIdDesc(
                        assignment.id().demoRunId(), assignment.id().tripId(), "SIMULATION")
                .filter(arrival -> arrival.getEstimatedArrivalAt() != null)
                .map(arrival -> new CaptainRoutePolicy.Progress(value.stopSequence(), arrival.getEstimatedArrivalAt(),
                        value.observedAt(), value.assumedSpeedKmh())));
        var plan = policy.plan(stops, progress, clock.instant(), Duration.ofSeconds(polling.staleAfterSeconds()));
        return Optional.of(new CaptainRoute(assignment.busId(), assignment.vehicleRegistrationNumber(),
                assignment.serviceId(), assignment.directionId(), plan.stops(), plan.position()));
    }

    private static Optional<UUID> parse(String subject) {
        try {
            return Optional.of(UUID.fromString(subject));
        } catch (IllegalArgumentException | NullPointerException exception) {
            // Supabase subjects are UUIDs; any other subject simply has no simulation assignment.
            return Optional.empty();
        }
    }
}
