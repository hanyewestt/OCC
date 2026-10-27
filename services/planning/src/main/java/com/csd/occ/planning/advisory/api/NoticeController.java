package com.csd.occ.planning.advisory.api;

import com.csd.occ.planning.advisory.api.dto.*;
import com.csd.occ.planning.advisory.domain.PublicNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/planning") @Tag(name = "Public advisories")
@SecurityRequirement(name = "SupabaseJWT")
public class NoticeController {
    private final PublicNoticeService notices;
    public NoticeController(PublicNoticeService notices) { this.notices = notices; }
    @GetMapping("/notices")
    @Operation(operationId = "listManagedNotices", summary = "Review notice drafts and publication history", description = "Controller/admin only; newest first; page 0, size 20 (1–100).")
    public NoticeResponse.PageResponse list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = notices.managed(page, size);
        return new NoticeResponse.PageResponse(result.map(NoticeResponse::from).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    @PostMapping("/notices")
    @Operation(operationId = "draftNotice", summary = "Create a controller-reviewed notice draft",
            description = "Controller/admin only. Client UUID identifies an idempotent draft; different content under the same UUID returns 409. Source determines provenance; actor comes from JWT.")
    public NoticeResponse draft(@RequestBody @Valid CreateNoticeRequest body, @AuthenticationPrincipal Jwt jwt) {
        return NoticeResponse.from(notices.draft(body.id(), body.disruptionId(), body.title(), body.message(), body.services(),
                body.locations(), body.effectiveFrom(), body.effectiveUntil(), jwt.getSubject()));
    }
    @PostMapping("/notices/{id}/suggestions")
    @Operation(operationId = "suggestNoticeText", summary = "Suggest passenger-facing notice text for controller review",
            description = "Controller/admin only. Uses deterministic source facts when AI is disabled; enabled AI receives no bus, captain, account or run identifiers. This endpoint never creates or publishes a notice.")
    public NoticeSuggestionResponse suggest(@PathVariable("id") UUID disruptionId) {
        return NoticeSuggestionResponse.from(notices.suggest(disruptionId));
    }
    @PostMapping("/notices/{id}/publications")
    @Operation(operationId = "publishNotice", summary = "Publish an approved notice", description = "Controller/admin only. Published content is immutable. Repeating returns the original publication timestamp.")
    @ApiResponse(responseCode = "404", description = "Notice not found", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Notice is not eligible for publication", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    public NoticeResponse publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return NoticeResponse.from(notices.publish(id, jwt.getSubject())); }
    @PostMapping("/notices/{id}/withdrawals")
    @Operation(operationId = "withdrawNotice", summary = "Withdraw a public notice", description = "Controller/admin only. Repeated withdrawal is safe; public reads exclude it immediately.")
    public NoticeResponse withdraw(@PathVariable UUID id) { return NoticeResponse.from(notices.withdraw(id)); }
    @PostMapping("/notices/{id}/replacements")
    @Operation(operationId = "replaceNotice", summary = "Publish a correction and withdraw the old notice atomically",
            description = "Controller/admin only. New UUID and same disruption source required. Repeating the same replacement is idempotent.")
    public NoticeResponse replace(@PathVariable UUID id, @RequestBody @Valid CreateNoticeRequest body, @AuthenticationPrincipal Jwt jwt) {
        return NoticeResponse.from(notices.replace(id, body.id(), body.disruptionId(), body.title(), body.message(), body.services(),
                body.locations(), body.effectiveFrom(), body.effectiveUntil(), jwt.getSubject()));
    }
    @GetMapping("/public/notices")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @Operation(operationId = "listPublishedNotices", summary = "Read current approved passenger notices anonymously",
            description = "No authentication required. Default OBSERVATION_ONLY excludes all synthetic notices. SIMULATION requires demoRunId. "
                    + "Only published notices within [effectiveFrom,effectiveUntil) appear. page defaults 0; size defaults 20 (1–100).")
    @ApiResponse(responseCode = "200", description = "Approved current passenger-facing notices")
    @ApiResponse(responseCode = "400", description = "Invalid classification, run scope or pagination")
    public PublishedNoticeResponse.PageResponse published(@RequestParam(defaultValue = "OBSERVATION_ONLY") String classification,
            @RequestParam(required = false) UUID demoRunId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = notices.current(classification, demoRunId, page, size);
        return new PublishedNoticeResponse.PageResponse(result.map(PublishedNoticeResponse::from).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }
}
