package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import com.csd.occ.planning.domain.audit.AuditEvidenceSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentOperationFinalizer {
    private final AssignmentOperationRepository operations;
    private final AssignmentBusGuards guards;
    private final ApplicationAuditEventRepository audit;
    private final Clock clock;

    public AssignmentOperationFinalizer(AssignmentOperationRepository operations, AssignmentBusGuards guards,
            ApplicationAuditEventRepository audit, Clock clock) {
        this.operations=operations; this.guards=guards; this.audit=audit; this.clock=clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignmentOperation finalizeReceipt(UUID operationId, AssignmentIngestionPort.Receipt receipt) {
        var operationSnapshot = operations.findById(operationId).orElseThrow(AssignmentOperationNotFoundException::new);
        var guard = guards.lock(operationSnapshot.demoRunId(), operationSnapshot.busId());
        var operation = operations.findForUpdate(operationId).orElseThrow(AssignmentOperationNotFoundException::new);
        if (!"PENDING".equals(operation.status())) return operation;
        boolean matchingCommand = operation.id().equals(receipt.operationId()) && operation.demoRunId().equals(receipt.runId())
                && operation.busId().equals(receipt.busId())
                && java.util.Objects.equals(operation.captainUserId(), receipt.captainUserId());
        boolean validVersion = switch (receipt.outcome()) {
            case "APPLIED" -> receipt.beforeVersion() == operation.expectedVersion()
                    && receipt.afterVersion() == receipt.beforeVersion() + 1;
            case "NO_OP" -> receipt.beforeVersion() == operation.expectedVersion()
                    && receipt.afterVersion() == receipt.beforeVersion();
            case "REJECTED" -> receipt.afterVersion() == receipt.beforeVersion();
            default -> false;
        };
        if (!matchingCommand || !validVersion)
            return operation;
        String state = "REJECTED".equals(receipt.outcome()) ? "REJECTED" : "SUCCEEDED";
        Map<String, Object> result = receiptSnapshot(receipt);
        operation.finish(state, result, clock.instant());
        guard.clear(operation.id());
        String sourceKey = "assignment-operation:" + operation.id() + ":" + state;
        if (!audit.existsBySourceKey(sourceKey)) {
            audit.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8)),
                    clock.instant(), clock.instant(), "ACCOUNT_ASSIGNMENT", "BUS_ASSIGNMENT_" + state,
                    receipt.outcome(), operation.actorSubject(), "ADMIN", "DEMO_BUS", operation.busId(),
                    operation.demoRunId(), operation.busId(), receipt.captainUserId(), operation.id().toString(),
                    "Demo bus assignment operation " + state.toLowerCase(java.util.Locale.ROOT), result, sourceKey));
        }
        return operation;
    }

    private static Map<String, Object> receiptSnapshot(AssignmentIngestionPort.Receipt receipt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", receipt.outcome()); result.put("previousCaptainUserId", receipt.previousCaptainUserId());
        result.put("captainUserId", receipt.captainUserId()); result.put("beforeVersion", receipt.beforeVersion());
        result.put("afterVersion", receipt.afterVersion());
        if (receipt.reason() != null) result.put("reason", AuditEvidenceSanitizer.sanitizeText(receipt.reason()));
        return result;
    }
}
