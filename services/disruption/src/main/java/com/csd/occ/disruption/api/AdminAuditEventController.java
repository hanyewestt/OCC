package com.csd.occ.disruption.api;

import com.csd.occ.disruption.api.dto.AdminAuditEventPageResponse;
import com.csd.occ.disruption.api.dto.AdminAuditEventResponse;
import com.csd.occ.disruption.api.dto.AdminAuditEventSummaryResponse;
import com.csd.occ.disruption.domain.audit.ApplicationAuditEvent;
import com.csd.occ.disruption.domain.audit.ApplicationAuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1/disruption/admin/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin application audit")
public class AdminAuditEventController {
    private final ApplicationAuditQueryService audit;

    public AdminAuditEventController(ApplicationAuditQueryService audit) { this.audit = audit; }

    @GetMapping
    @Operation(operationId = "listDisruptionAdminAuditEvents", summary = "Search retained Disruption application audit events")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filtered page of retained events, with details omitted",
                    content = @Content(schema = @Schema(implementation = AdminAuditEventPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid filter, page or time range"),
            @ApiResponse(responseCode = "401", description = "Authentication is required"),
            @ApiResponse(responseCode = "403", description = "Only administrators may read the admin audit")})
    public AdminAuditEventPageResponse list(
            @Parameter(description = "Exact event category") @RequestParam(required = false) String category,
            @Parameter(description = "Exact event type") @RequestParam(required = false) String eventType,
            @Parameter(description = "Exact event outcome") @RequestParam(required = false) String outcome,
            @Parameter(description = "Exact actor subject identifier") @RequestParam(required = false) String actorSubject,
            @Parameter(description = "Simulation run identifier") @RequestParam(required = false) UUID runId,
            @Parameter(description = "Exact entity identifier") @RequestParam(required = false) String entityId,
            @Parameter(description = "Inclusive occurrence-time lower bound; defaults to the last 24 hours")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Inclusive occurrence-time upper bound; defaults to now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Zero-based page, default 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, default 20 and maximum 100") @RequestParam(defaultValue = "20") int size) {
        Page<ApplicationAuditEvent> results;
        try {
            results = audit.search(category, eventType, outcome, actorSubject, runId, entityId, from, to, page, size);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit search parameters");
        }
        return new AdminAuditEventPageResponse(results.getContent().stream().map(AdminAuditEventSummaryResponse::from).toList(),
                results.getNumber(), results.getSize(), results.getTotalElements(), results.getTotalPages(),
                audit.retention().toSeconds(), audit.collectionStartedAt());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getDisruptionAdminAuditEvent", summary = "Read one retained Disruption audit event with sanitized evidence")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Retained event with sanitized details",
                    content = @Content(schema = @Schema(implementation = AdminAuditEventResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication is required"),
            @ApiResponse(responseCode = "403", description = "Only administrators may read the admin audit"),
            @ApiResponse(responseCode = "404", description = "Event is missing or outside the retention window")})
    public AdminAuditEventResponse detail(@PathVariable UUID id) {
        ApplicationAuditEvent event = audit.findRetained(id);
        if (event == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found");
        return AdminAuditEventResponse.detail(event);
    }
}
