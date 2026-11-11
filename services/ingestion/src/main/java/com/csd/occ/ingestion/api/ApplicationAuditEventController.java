package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.AuditEventDetailResponse;
import com.csd.occ.ingestion.api.dto.AuditEventPageResponse;
import com.csd.occ.ingestion.api.dto.AuditEventSummaryResponse;
import com.csd.occ.ingestion.domain.ApplicationAuditEvent;
import com.csd.occ.ingestion.domain.ApplicationAuditQueryService;
import com.csd.occ.ingestion.domain.AuditEvidenceSanitizer;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1/admin/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Application audit")
@ApiResponse(responseCode = "401", description = "Missing or invalid authentication", content = @Content)
@ApiResponse(responseCode = "403", description = "Authenticated role cannot read the audit history", content = @Content)
public class ApplicationAuditEventController {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ApplicationAuditQueryService audit;

    public ApplicationAuditEventController(ApplicationAuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listApplicationAuditEvents", summary = "List retained Ingestion audit events",
            description = "ADMIN only. Filters apply before pagination. Defaults to the last 24 hours and never returns events older than the configured audit retention window. Results sort by occurrence time descending, then event ID descending. Summary rows exclude evidence details.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filtered audit page returned",
                    content = @Content(schema = @Schema(implementation = AuditEventPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid filter, date range or pagination",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<AuditEventPageResponse> list(
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
        validate(page, size, from, to);
        var result = audit.search(new ApplicationAuditQueryService.AuditFilter(category, eventType, outcome,
                actorSubject, runId, entityId, from, to), page, size);
        var source = result.page();
        var body = new AuditEventPageResponse(source.getContent().stream().map(this::summary).toList(),
                source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages(),
                result.retentionSeconds(), result.collectionStartedAt());
        return noStore(body);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getApplicationAuditEvent", summary = "Read one retained Ingestion audit event",
            description = "ADMIN only. Returns event-time metadata and sanitized evidence details.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit detail returned",
                    content = @Content(schema = @Schema(implementation = AuditEventDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Malformed event UUID", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event is missing or outside the retention window",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<AuditEventDetailResponse> detail(@PathVariable UUID id) {
        ApplicationAuditEvent event = audit.detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found"));
        var body = new AuditEventDetailResponse(event.id(), event.occurredAt(), event.recordedAt(), event.category(),
                event.eventType(), event.outcome(), event.sourceService(), event.actorSubject(), event.actorType(),
                event.entityType(), event.entityId(), event.runId(), event.busId(), event.captainId(),
                event.correlationId(), safeSummary(event.summary()), event.backfilled(), AuditEvidenceSanitizer.sanitize(event.details()));
        return noStore(body);
    }

    private AuditEventSummaryResponse summary(ApplicationAuditEvent event) {
        return new AuditEventSummaryResponse(event.id(), event.occurredAt(), event.recordedAt(), event.category(),
                event.eventType(), event.outcome(), event.sourceService(), event.actorSubject(), event.actorType(),
                event.entityType(), event.entityId(), event.runId(), event.busId(), event.captainId(),
                event.correlationId(), safeSummary(event.summary()), event.backfilled());
    }

    private static String safeSummary(String summary) {
        return (String) AuditEvidenceSanitizer.sanitize(Map.of("summary", summary)).get("summary");
    }

    private static void validate(int page, int size, Instant from, Instant to) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit page or size");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
