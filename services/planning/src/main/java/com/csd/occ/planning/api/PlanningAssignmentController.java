package com.csd.occ.planning.api;

import com.csd.occ.planning.advisory.domain.AssignmentOperation;
import com.csd.occ.planning.advisory.domain.AssignmentOperationCoordinator;
import com.csd.occ.planning.advisory.domain.PlanningActor;
import com.csd.occ.planning.advisory.api.PlanningActorResolver;
import com.csd.occ.planning.api.dto.AssignmentOperationResponse;
import com.csd.occ.planning.api.dto.AssignmentRequest;
import com.csd.occ.planning.api.dto.PendingAssignmentOperationsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo bus assignments")
public class PlanningAssignmentController {
    private final AssignmentOperationCoordinator operations;
    public PlanningAssignmentController(AssignmentOperationCoordinator operations) { this.operations = operations; }

    @PutMapping(value = "/api/v1/planning/admin/demo-runs/{runId}/buses/{busId}/assignment",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "SupabaseJWT")
    @Operation(operationId = "reassignDemoBus", summary = "Assign or unassign a captain on an active demo bus",
            description = "ADMIN only. The required Idempotency-Key identifies this immutable operation. Explicit null unassigns; an omitted captainUserId is invalid. Uncertain dispatch returns 202 and keeps the bus reserved until recovery confirms a receipt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assignment committed or a terminal replay returned", content = @Content(schema = @Schema(implementation = AssignmentOperationResponse.class))),
            @ApiResponse(responseCode = "202", description = "Assignment outcome is pending; poll the operation", content = @Content(schema = @Schema(implementation = AssignmentOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payload or idempotency key", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "409", description = "Bus, revision, key or outstanding-instruction conflict", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Authoritative assignment eligibility could not be verified", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<AssignmentOperationResponse> assign(@PathVariable UUID runId, @PathVariable String busId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid AssignmentRequest body, @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        PlanningActor actor = PlanningActorResolver.from(jwt, authentication);
        AssignmentOperation operation = operations.reserve(actor, runId, busId, body.captainUserId(),
                body.expectedVersion(), idempotencyKey);
        if ("PENDING".equals(operation.status())) operation = operations.dispatch(operation.id());
        var result = AssignmentOperationResponse.from(operation);
        return switch (operation.status()) {
            case "SUCCEEDED" -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
            case "REJECTED" -> throw new com.csd.occ.planning.advisory.domain.AssignmentOperationConflictException(
                    "Bus assignment was rejected", operation.result());
            default -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
        };
    }

    @GetMapping(value = "/api/v1/planning/admin/assignment-operations/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "SupabaseJWT")
    @Operation(operationId = "getAssignmentOperation", summary = "Read a durable bus assignment operation")
    public AssignmentOperationResponse status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        return AssignmentOperationResponse.from(operations.find(PlanningActorResolver.from(jwt, authentication), id));
    }

    @GetMapping(value = "/internal/v1/planning/demo-runs/{runId}/pending-assignment-operations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "IngestionPeerToken")
    @Operation(operationId = "listPendingAssignmentOperations", summary = "List pending Planning assignment reservations",
            description = "Planning-peer only. Contains only run, bus, operation and status identifiers for Ingestion's active assignment projection.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending bus reservations returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid peer capability"),
            @ApiResponse(responseCode = "403", description = "Peer capability is not authorized for this route")})
    public PendingAssignmentOperationsResponse pending(@PathVariable UUID runId) {
        return new PendingAssignmentOperationsResponse(operations.pending(runId));
    }
}
