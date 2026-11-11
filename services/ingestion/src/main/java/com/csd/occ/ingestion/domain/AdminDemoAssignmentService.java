package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminDemoAssignmentService {
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final PendingAssignmentOperationsPort pending;

    public AdminDemoAssignmentService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            PendingAssignmentOperationsPort pending) {
        this.runs = runs; this.assignments = assignments; this.pending = pending;
    }

    @Transactional(readOnly = true)
    public ActiveDemoAssignments active() {
        var run = runs.findActive().filter(value -> "RUNNING".equals(value.status())
                || "REPLAY_COMPLETE".equals(value.status())).orElse(null);
        if (run == null) return new ActiveDemoAssignments(null, null, List.of());
        try {
            List<PendingAssignmentOperationsPort.Pending> pendingOperations = pending.findPending(run.id());
            Map<String, PendingAssignmentOperationsPort.Pending> byBus = pendingOperations.stream()
                    .collect(Collectors.toMap(PendingAssignmentOperationsPort.Pending::busId, Function.identity()));
            var page = assignments.findByIdDemoRunIdOrderByBusId(run.id(), PageRequest.of(0, 100));
            var items = page.getContent().stream().map(assignment -> new ActiveDemoAssignments.Bus(
                    assignment.busId(), assignment.id().tripId(), assignment.vehicleRegistrationNumber(),
                    assignment.serviceId(), assignment.directionId(), assignment.captainUserId().orElse(null),
                    assignment.assignmentRevision(), byBus.get(assignment.busId()))).toList();
            return new ActiveDemoAssignments(run.id(), run.status(), items);
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Assignment coordination status is unavailable");
        }
    }
}
