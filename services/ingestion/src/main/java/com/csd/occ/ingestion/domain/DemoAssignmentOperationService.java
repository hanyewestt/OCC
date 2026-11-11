package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoAssignmentOperationService {
    private final DemoAssignmentReceiptRepository receipts;
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final IdentityProviderPort identities;
    private final ApplicationAuditEventRepository auditEvents;
    private final Clock clock;

    public DemoAssignmentOperationService(DemoAssignmentReceiptRepository receipts, DemoRunRepository runs,
            DemoVehicleAssignmentRepository assignments, IdentityProviderPort identities,
            ApplicationAuditEventRepository auditEvents, Clock clock) {
        this.receipts = receipts; this.runs = runs; this.assignments = assignments;
        this.identities = identities; this.auditEvents = auditEvents; this.clock = clock;
    }

    @Transactional
    public Result apply(UUID operationId, UUID runId, String busId, UUID captainId,
            long expectedVersion, String actorSubject) {
        receipts.claim(operationId, runId, busId, captainId, expectedVersion, actorSubject, clock.instant());
        DemoAssignmentReceipt receipt = receipts.findLocked(operationId).orElseThrow();
        if (!receipt.matches(runId, busId, captainId, expectedVersion, actorSubject))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID was used for a different assignment request");
        if (receipt.completed()) return new Result(receipt, receipt.httpStatus());

        var run = runs.findByIdForUpdate(runId).orElse(null);
        if (run == null || !("RUNNING".equals(run.status()) || "REPLAY_COMPLETE".equals(run.status())))
            return reject(receipt, run == null ? "RUN_NOT_FOUND" : "RUN_NOT_ACTIVE", null, expectedVersion);
        var assignment = assignments.findByRunIdAndBusIdForUpdate(runId, busId).orElse(null);
        if (assignment == null) return reject(receipt, "BUS_NOT_FOUND", null, expectedVersion);

        long version = assignment.assignmentRevision();
        UUID previous = assignment.captainUserId().orElse(null);
        if (version != expectedVersion) return reject(receipt, "STALE_VERSION", previous, version);
        if (captainId != null) {
            IdentityProviderPort.User user;
            try {
                user = identities.get(captainId).orElse(null);
            } catch (IdentityProviderException known) {
                if (known.reason() == IdentityProviderException.Reason.UNAVAILABLE) throw known;
                throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
            } catch (RuntimeException unavailable) {
                throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
            }
            if (user == null || !user.available() || user.role() != OccRole.CAPTAIN)
                return reject(receipt, "CAPTAIN_INELIGIBLE", previous, version);
            var otherBus = assignments.findByIdDemoRunIdAndCaptainUserId(runId, captainId);
            if (otherBus.isPresent() && !otherBus.get().id().equals(assignment.id()))
                return reject(receipt, "CAPTAIN_ALREADY_ASSIGNED", previous, version);
        }

        boolean changed = assignment.assignCaptain(captainId);
        long after = assignment.assignmentRevision();
        String outcome = changed ? "APPLIED" : "NO_OP";
        receipt.finish(outcome, 200, previous, version, after, null);
        recordAudit(operationId, runId, busId, actorSubject,
                changed ? "BUS_ASSIGNMENT_CHANGED" : "BUS_ASSIGNMENT_NO_OP", "SUCCESS",
                previous, captainId, version, after, null);
        return new Result(receipt, 200);
    }

    private Result reject(DemoAssignmentReceipt receipt, String reason, UUID previous, long version) {
        receipt.finish("REJECTED", HttpStatus.CONFLICT.value(), previous, version, version, reason);
        recordAudit(receipt.operationId(), receipt.demoRunId(), receipt.busId(), receipt.actorSubject(),
                "BUS_ASSIGNMENT_REJECTED", "FAILURE", previous, receipt.captainUserId(), version, version, reason);
        return new Result(receipt, HttpStatus.CONFLICT.value());
    }

    private void recordAudit(UUID operationId, UUID runId, String busId, String actorSubject, String eventType,
            String outcome, UUID previous, UUID captain, long before, long after, String reason) {
        var now = clock.instant();
        var details = new java.util.HashMap<String, Object>();
        details.put("previousCaptainUserId", previous == null ? "UNASSIGNED" : previous.toString());
        details.put("captainUserId", captain == null ? "UNASSIGNED" : captain.toString());
        details.put("beforeVersion", before);
        details.put("afterVersion", after);
        if (reason != null) details.put("reason", reason);
        auditEvents.save(new ApplicationAuditEvent(UUID.randomUUID(), now, now, "ACCOUNT_ASSIGNMENT", eventType,
                outcome, "INGESTION", actorSubject, "ADMIN", "DEMO_BUS", busId, runId, busId,
                captain, operationId.toString(), "Demo bus assignment operation " + outcome.toLowerCase(),
                details, "demo-assignment:" + operationId));
    }

    public record Result(DemoAssignmentReceipt receipt, int httpStatus) { }
}
