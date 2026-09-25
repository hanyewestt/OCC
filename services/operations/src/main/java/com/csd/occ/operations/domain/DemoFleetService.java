package com.csd.occ.operations.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoFleetService {
    private final DemoRunRepository runs;
    private final DemoVehicleRepository vehicles;
    private final DemoDriverRepository drivers;
    private final DemoAssignmentRepository assignments;
    private final RouteReferencePort routeReference;
    private final DemoRoutePolicy routePolicy;
    private final Clock clock;

    public DemoFleetService(DemoRunRepository runs, DemoVehicleRepository vehicles,
            DemoDriverRepository drivers, DemoAssignmentRepository assignments,
            RouteReferencePort routeReference, DemoRoutePolicy routePolicy, Clock clock) {
        this.runs = runs;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.assignments = assignments;
        this.routeReference = routeReference;
        this.routePolicy = routePolicy;
        this.clock = clock;
    }

    @Transactional
    public DemoRun createRun(String serviceId, int directionId, String actorSub) {
        var route = routeReference.route(serviceId)
                .orElseThrow(() -> new InvalidDemoRouteException("Service is absent from active route topology"));
        if (!routePolicy.hasDirection(route, directionId)) {
            throw new InvalidDemoRouteException("Direction is absent from active route topology");
        }
        DemoRun run = new DemoRun(UUID.randomUUID(), serviceId, directionId, actorSub, Instant.now(clock));
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public DemoRun run(UUID id) {
        return runs.findById(id).orElseThrow(DemoRunNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public DemoRoute routeForRun(UUID runId) {
        DemoRun run = runs.findById(runId).orElseThrow(DemoRunNotFoundException::new);
        var reference = routeReference.route(run.getServiceId())
                .orElseThrow(() -> new DemoRunConflictException("Selected service is absent from active route topology"));
        List<RouteReferencePort.Direction> directions = reference.directions().stream()
                .filter(direction -> direction.directionId() == run.getDirectionId())
                .toList();
        if (directions.isEmpty()) {
            throw new DemoRunConflictException("Selected direction is absent from active route topology");
        }
        return new DemoRoute(run, directions);
    }

    @Transactional
    public Placement place(UUID runId, String displayLabel, String syntheticPlate,
            BigDecimal latitude, BigDecimal longitude, String driverDisplayName) {
        DemoRun run = runs.findById(runId).orElseThrow(DemoRunNotFoundException::new);
        if (!"DRAFT".equals(run.getStatus())) {
            throw new DemoRunConflictException("Vehicles may be added only to a draft demo run");
        }
        var route = routeReference.route(run.getServiceId())
                .orElseThrow(() -> new InvalidDemoRouteException("Service is absent from active route topology"));
        if (!routePolicy.isNearRouteStop(route, run.getDirectionId(), latitude, longitude)) {
            throw new InvalidDemoRouteException("Demo position must be near a stop on the selected direction");
        }
        Instant now = Instant.now(clock);
        DemoVehicle vehicle = vehicles.save(new DemoVehicle(UUID.randomUUID(), runId, displayLabel,
                syntheticPlate, latitude, longitude, now));
        DemoDriver driver = drivers.save(new DemoDriver(UUID.randomUUID(), runId, driverDisplayName, now));
        DemoAssignment assignment = assignments.save(new DemoAssignment(UUID.randomUUID(), runId,
                vehicle.getId(), driver.getId(), now));
        return new Placement(assignment, vehicle, driver);
    }

    @Transactional(readOnly = true)
    public Page<Placement> placements(UUID runId, Pageable pageable) {
        if (!runs.existsById(runId)) throw new DemoRunNotFoundException();
        return assignments.findByRunId(runId, pageable).map(this::placement);
    }

    @Transactional(readOnly = true)
    public Placement placement(UUID runId, UUID vehicleId) {
        DemoAssignment assignment = assignments.findByVehicleId(vehicleId)
                .filter(found -> found.getRunId().equals(runId))
                .orElseThrow(DemoRunNotFoundException::new);
        return placement(assignment);
    }

    private Placement placement(DemoAssignment assignment) {
        return new Placement(assignment,
                vehicles.findById(assignment.getVehicleId()).orElseThrow(),
                drivers.findById(assignment.getDriverId()).orElseThrow());
    }

    public record Placement(DemoAssignment assignment, DemoVehicle vehicle, DemoDriver driver) { }
    public record DemoRoute(DemoRun run, List<RouteReferencePort.Direction> directions) { }
}
