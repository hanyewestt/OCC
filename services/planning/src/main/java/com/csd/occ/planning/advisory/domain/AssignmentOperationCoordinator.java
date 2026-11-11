package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentOperationCoordinator {
    private final AssignmentOperationRepository operations;
    private final AssignmentBusGuards guards;
    private final AdvisoryContextPort context;
    private final InstructionService instructions;
    private final AssignmentIngestionPort ingestion;
    private final AssignmentOperationFinalizer finalizer;
    private final ApplicationAuditEventRepository auditEvents;
    private final Clock clock;

    public AssignmentOperationCoordinator(AssignmentOperationRepository operations, AssignmentBusGuards guards,
            AdvisoryContextPort context, InstructionService instructions, AssignmentIngestionPort ingestion,
            AssignmentOperationFinalizer finalizer, ApplicationAuditEventRepository auditEvents, Clock clock) {
        this.operations = operations; this.guards = guards; this.context = context; this.instructions = instructions;
        this.ingestion = ingestion; this.finalizer = finalizer; this.auditEvents = auditEvents; this.clock = clock;
    }

    @Transactional
    public AssignmentOperation reserve(PlanningActor actor, UUID runId, String busId, UUID captainUserId,
            long expectedVersion, String idempotencyKey) {
        actor.require(PlanningActor.Role.ADMIN);
        validateKey(idempotencyKey);
        var prior = operations.findByActorSubjectAndIdempotencyKey(actor.subject(), idempotencyKey);
        if (prior.isPresent()) return replay(prior.get(), runId, busId, captainUserId, expectedVersion);

        var guard = guards.lock(runId, busId);
        UUID operationId = UUID.randomUUID();
        Instant now = clock.instant();
        int inserted = operations.insertPendingIfAbsent(operationId, actor.subject(), idempotencyKey, runId, busId,
                captainUserId, expectedVersion, now);
        if (inserted == 0) {
            var concurrent = operations.findByActorSubjectAndIdempotencyKey(actor.subject(), idempotencyKey).orElseThrow();
            return replay(concurrent, runId, busId, captainUserId, expectedVersion);
        }
        var operation = operations.findForUpdate(operationId).orElseThrow();

        if (guard.pendingOperationId() != null) {
            finishRejected(operation, "BUSY", Map.of("blockingOperationId", guard.pendingOperationId().toString()));
            return operation;
        }
        AdvisoryContextPort.Fleet fleet;
        try {
            fleet = context.fleet(runId);
        } catch (RuntimeException unavailable) {
            throw new AssignmentEligibilityUnavailableException();
        }
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) {
            finishRejected(operation, "RUN_CLOSED", Map.of());
            return operation;
        }
        var bus = fleet.items().stream().filter(item -> item.busId().equals(busId)).findFirst();
        if (bus.isEmpty()) {
            finishRejected(operation, "BUS_NOT_FOUND", Map.of());
            return operation;
        }
        if (bus.get().assignmentRevision() != expectedVersion) {
            finishRejected(operation, "STALE_VERSION", Map.of("currentVersion", bus.get().assignmentRevision()));
            return operation;
        }
        List<Map<String, Object>> blocking;
        try {
            blocking = instructions.currentBlockersForAssignment(runId, busId, bus.get());
        } catch (RuntimeException unavailable) {
            throw new AssignmentEligibilityUnavailableException();
        }
        if (!blocking.isEmpty()) {
            finishRejected(operation, "OUTSTANDING_INSTRUCTIONS", Map.of("blockingInstructions", blocking));
            return operation;
        }
        guard.reserve(operation.id());
        return operation;
    }

    public AssignmentOperation dispatch(UUID operationId) {
        AssignmentOperation pending = operations.findById(operationId).orElseThrow(AssignmentOperationNotFoundException::new);
        if (!"PENDING".equals(pending.status())) return pending;
        if (operations.markDispatchAttempt(operationId, clock.instant()) == 0)
            return operations.findById(operationId).orElseThrow(AssignmentOperationNotFoundException::new);
        AssignmentIngestionPort.Receipt receipt;
        try {
            receipt = ingestion.apply(new AssignmentIngestionPort.Command(pending.id(), pending.demoRunId(), pending.busId(),
                    pending.captainUserId(), pending.expectedVersion(), pending.actorSubject()));
        } catch (RuntimeException uncertain) {
            return pending;
        }
        return finalizer.finalizeReceipt(operationId, receipt);
    }

    @Transactional
    public AssignmentOperation find(PlanningActor actor, UUID operationId) {
        actor.require(PlanningActor.Role.ADMIN);
        return operations.findById(operationId).orElseThrow(AssignmentOperationNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<PendingAssignment> pending(UUID runId) {
        return operations.findByRunAndStatus(runId, "PENDING", PageRequest.of(0, 500)).stream()
                .map(operation -> new PendingAssignment(operation.busId(), operation.id(), operation.status())).toList();
    }

    public int recoverPending(int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid assignment recovery batch size");
        List<AssignmentOperation> pending = operations.findByStatusOrderByCreatedAtAscIdAsc("PENDING", PageRequest.of(0, batchSize));
        for (var operation : pending) dispatch(operation.id());
        return pending.size();
    }

    private AssignmentOperation replay(AssignmentOperation operation, UUID runId, String busId, UUID captainUserId,
            long expectedVersion) {
        if (!operation.matches(runId, busId, captainUserId, expectedVersion))
            throw new AssignmentOperationConflictException("Idempotency key payload changed", Map.of("operationId", operation.id().toString()));
        return operation;
    }

    private void finishRejected(AssignmentOperation operation, String code, Map<String, Object> details) {
        var result = new LinkedHashMap<String, Object>(); result.put("reasonCode", code); result.putAll(details);
        operation.finish("REJECTED", result, clock.instant());
        String sourceKey = "assignment-operation:" + operation.id() + ":REJECTED";
        if (!auditEvents.existsBySourceKey(sourceKey)) {
            auditEvents.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    clock.instant(), clock.instant(), "ACCOUNT_ASSIGNMENT", "BUS_ASSIGNMENT_REJECTED", code,
                    operation.actorSubject(), "ADMIN", "DEMO_BUS", operation.busId(), operation.demoRunId(),
                    operation.busId(), null, operation.id().toString(), "Demo bus assignment rejected", result, sourceKey));
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Invalid Idempotency-Key");
    }

    public record PendingAssignment(String busId, UUID operationId, String status) { }
}
