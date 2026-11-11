package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.DemoAssignmentOperationRequest;
import com.csd.occ.ingestion.api.dto.DemoAssignmentOperationResponse;
import com.csd.occ.ingestion.domain.DemoAssignmentOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Internal demo assignment coordination")
public class InternalDemoAssignmentOperationController {
    private final DemoAssignmentOperationService operations;
    public InternalDemoAssignmentOperationController(DemoAssignmentOperationService operations) { this.operations = operations; }

    @PutMapping("/internal/v1/ingestion/demo-runs/{runId}/buses/{busId}/assignment-operations/{operationId}")
    @Operation(operationId = "applyInternalDemoAssignment", summary = "Apply or replay a guarded demo assignment operation",
            description = "Planning peer capability only. The operation receipt is durable across demo-run cleanup.")
    @SecurityRequirement(name = "OCCPeerCapability")
    @ApiResponse(responseCode = "200", description = "Assignment applied, unchanged, or a prior receipt replayed")
    @ApiResponse(responseCode = "401", description = "Missing or invalid peer capability")
    @ApiResponse(responseCode = "403", description = "Peer capability is not authorized for this route")
    @ApiResponse(responseCode = "409", description = "Run, bus, revision, or captain precondition rejected")
    @ApiResponse(responseCode = "503", description = "Captain eligibility could not be established")
    public ResponseEntity<DemoAssignmentOperationResponse> apply(@PathVariable UUID runId, @PathVariable String busId,
            @PathVariable UUID operationId, @Valid @RequestBody DemoAssignmentOperationRequest request) {
        var result = operations.apply(operationId, runId, busId, request.captainUserId(),
                request.expectedVersion(), request.actorSubject());
        var receipt = result.receipt();
        var response = new DemoAssignmentOperationResponse(receipt.operationId(), receipt.demoRunId(), receipt.busId(),
                DemoAssignmentOperationResponse.Outcome.valueOf(receipt.outcome()), receipt.previousCaptainUserId(),
                receipt.captainUserId(), receipt.beforeVersion(), receipt.afterVersion(), receipt.reason());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(response);
    }
}
