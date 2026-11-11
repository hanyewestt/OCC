package com.csd.occ.planning.api;

import com.csd.occ.planning.api.dto.PlanningAuditEventDetailResponse;
import com.csd.occ.planning.api.dto.PlanningAuditEventPageResponse;
import com.csd.occ.planning.api.dto.PlanningAuditEventSummaryResponse;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditQueryService;
import com.csd.occ.planning.domain.audit.AuditEvidenceSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/planning/admin/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Application audit")
public class ApplicationAuditEventController {
    private static final int MAX_PAGE_SIZE = 100;

    private final ApplicationAuditQueryService audit;

    public ApplicationAuditEventController(ApplicationAuditQueryService audit) { this.audit = audit; }

    @GetMapping
    @Operation(operationId = "listPlanningApplicationAuditEvents", summary = "List retained Planning audit events",
            description = "ADMIN only. Filters apply before pagination. Defaults to the last 24 hours and excludes events outside OCC_AUDIT_RETENTION. Results sort by occurrence time descending, then event ID descending. Summary rows exclude evidence details.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filtered audit page returned",
                    content = @Content(schema = @Schema(implementation = PlanningAuditEventPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid filter, date range or pagination",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication", content = @Content),
            @ApiResponse(responseCode = "403", description = "Authenticated role cannot read the audit history", content = @Content)})
    public ResponseEntity<PlanningAuditEventPageResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String actorSubject,
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) String entityId,
            @Parameter(description = "Inclusive start time; defaults to 24 hours before now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Inclusive end time; defaults to now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = audit.search(category, eventType, outcome, actorSubject, runId, entityId, from, to, page, size);
        var body = new PlanningAuditEventPageResponse(result.getContent().stream().map(this::summary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                audit.retentionSeconds(), audit.collectionStartedAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getPlanningApplicationAuditEvent", summary = "Read one retained Planning audit event",
            description = "ADMIN only. Returns sanitized event metadata and evidence. Reading AI processing evidence records a metadata-only access event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit detail returned",
                    content = @Content(schema = @Schema(implementation = PlanningAuditEventDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Malformed event UUID", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication", content = @Content),
            @ApiResponse(responseCode = "403", description = "Authenticated role cannot read the audit history", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event is missing or outside the retention window",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<PlanningAuditEventDetailResponse> detail(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        ApplicationAuditEvent event = audit.find(id, jwt.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PlanningAuditEventDetailResponse.from(event));
    }

    private PlanningAuditEventSummaryResponse summary(ApplicationAuditEvent event) {
        var safe = PlanningAuditEventSummaryResponse.from(event);
        return new PlanningAuditEventSummaryResponse(safe.id(), safe.eventSchemaVersion(), safe.occurredAt(),
                safe.recordedAt(), safe.category(), safe.eventType(), safe.outcome(), safe.sourceService(),
                safe.actorSubject(), safe.actorType(), safe.entityType(), safe.entityId(), safe.runId(), safe.busId(),
                safe.captainId(), safe.correlationId(), safeSummary(safe.summary()), safe.backfilled());
    }

    private static String safeSummary(String summary) {
        return (String) AuditEvidenceSanitizer.sanitize(java.util.Collections.singletonMap("summary", summary)).get("summary");
    }
}
