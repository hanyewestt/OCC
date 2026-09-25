package com.csd.occ.operations.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    public DemoFleetService(DemoRunRepository runs, DemoVehicleRepository vehicles,
            DemoDriverRepository drivers, DemoAssignmentRepository assignments, Clock clock) {
        this.runs = runs;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Transactional
    public DemoRun createRun(String serviceId, int directionId, String actorSub) {
        DemoRun run = new DemoRun(UUID.randomUUID(), serviceId, directionId, actorSub, Instant.now(clock));
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public DemoRun run(UUID id) {
        return runs.findById(id).orElseThrow(DemoRunNotFoundException::new);
    }

    @Transactional
    public Placement place(UUID runId, String displayLabel, String syntheticPlate,
            BigDecimal latitude, BigDecimal longitude, String driverDisplayName) {
        DemoRun run = runs.findById(runId).orElseThrow(DemoRunNotFoundException::new);
        if (!"DRAFT".equals(run.getStatus())) {
            throw new DemoRunConflictException("Vehicles may be added only to a draft demo run");
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
}
