package com.csd.occ.planning.diagnosis.api;

import com.csd.occ.planning.diagnosis.api.dto.CreateDiagnosisEventSubmission;
import com.csd.occ.planning.diagnosis.api.dto.DiagnosisRequestResponse;
import com.csd.occ.planning.diagnosis.api.dto.EarlyBunchingControllerSuggestionRequest;
import com.csd.occ.planning.diagnosis.api.dto.EarlyBunchingControllerSuggestionResponse;
import com.csd.occ.planning.diagnosis.api.dto.EarlyBunchingControllerSuggestionPageResponse;
import com.csd.occ.planning.diagnosis.api.dto.TriggerDiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisTriggerService;
import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestionService;
import com.csd.occ.planning.diagnosis.domain.EventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@Tag(name = "Root-cause diagnosis requests")
public class DiagnosisRequestController {
    private final DiagnosisRequestService requests;
    private final DiagnosisQueryService query;
    private final DiagnosisTriggerService triggerService;
    private final EarlyBunchingControllerSuggestionService suggestions;

    public DiagnosisRequestController(DiagnosisRequestService requests,
                                     DiagnosisQueryService query,
                                     DiagnosisTriggerService triggerService,
                                     EarlyBunchingControllerSuggestionService suggestions) {
        this.requests = requests;
        this.query = query;
        this.triggerService = triggerService;
        this.suggestions = suggestions;
    }

    @PostMapping("/internal/v1/planning/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "createDiagnosisRequest", summary = "Queue diagnosis for an immutable producer event",
            description = "The producer submits versioned immutable event facts. Planning stores the input before acknowledging. Bunching events are enriched from Ingestion; traffic incident events receive the explicit phase-1 no-action policy.")
    @ApiResponse(responseCode = "202", description = "Diagnosis request accepted or an existing request was returned")
    @ApiResponse(responseCode = "400", description = "Request validation failed, including a missing or invalid idempotency key or event fingerprint",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The idempotency key or event identity has different immutable facts",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Event type is unsupported or its evidence snapshot cannot be captured",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Temporary dependency failure while capturing the event evidence snapshot",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public DiagnosisRequestResponse create(@Valid @RequestBody CreateDiagnosisEventSubmission body,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "UUID reused when retrying this producer event")
            @RequestHeader(name = "Idempotency-Key") UUID idempotencyKey,
            @Parameter(name = "X-OCC-Event-Fingerprint", in = ParameterIn.HEADER, required = true,
                    description = "Lowercase SHA-256 fingerprint of the canonical typed event envelope",
                    schema = @Schema(pattern = "[0-9a-f]{64}"))
            @RequestHeader(name = "X-OCC-Event-Fingerprint") String fingerprint) {
        var request = requests.enqueue(body.toDomain(), idempotencyKey, fingerprint);
        var view = query.findRequest(request.id());
        return new DiagnosisRequestResponse(view.requestId(), view.diagnosisId(), view.status(), view.failureCode(),
                view.createdAt(), view.policyOutcome());
    }

    @GetMapping("/api/v1/planning/requests/{requestId}")
    @Operation(operationId = "getDiagnosisRequest", summary = "Get the request lifecycle status")
    @ApiResponse(responseCode = "200", description = "Diagnosis request status")
    @ApiResponse(responseCode = "404", description = "Diagnosis request not found")
    public DiagnosisRequestResponse get(@PathVariable UUID requestId) {
        var request = query.findRequest(requestId);
        return new DiagnosisRequestResponse(request.requestId(), request.diagnosisId(), request.status(),
                request.failureCode(), request.createdAt(), request.policyOutcome());
    }

    @GetMapping("/api/v1/planning/requests/by-event/{eventId}")
    @Operation(operationId = "getDiagnosisRequestByEvent", summary = "Get Planning status for a Disruption event",
            description = "Returns the current-version request, including terminal failures before a diagnosis exists. Defaults to a bus-bunching event.")
    @ApiResponse(responseCode = "200", description = "Diagnosis request status")
    @ApiResponse(responseCode = "404", description = "No request has been accepted for this event")
    public DiagnosisRequestResponse getByEvent(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "BUS_BUNCHING") EventType eventType) {
        var request = query.findRequestByEvent(eventId, eventType);
        return new DiagnosisRequestResponse(request.requestId(), request.diagnosisId(), request.status(),
                request.failureCode(), request.createdAt(), request.policyOutcome());
    }

    @PostMapping("/api/v1/planning/diagnoses/trigger")
    @Operation(operationId = "triggerDiagnosis", summary = "Trigger root-cause diagnosis on-demand for an event or request",
            description = "Durably queues diagnosis and returns its current lifecycle status immediately. The worker processes bunching, traffic and hazard requests using their respective policies, including retries and restart recovery when automatic bunching is disabled. Repeated triggers preserve active leases and retry backoff; completed requests return their existing status, including completion without a diagnosis. Poll GET /api/v1/planning/requests/{requestId} for completion. A terminal failure can be explicitly queued again.")
    @ApiResponse(responseCode = "200", description = "Diagnosis queued or existing lifecycle status returned")
    @ApiResponse(responseCode = "400", description = "Either eventId or requestId must be provided")
    @ApiResponse(responseCode = "404", description = "Diagnosis request not found")
    public DiagnosisRequestResponse trigger(@RequestBody TriggerDiagnosisRequest body) {
        var request = body.requestId() != null ? triggerService.trigger(body.requestId())
                : triggerService.triggerByEvent(body.eventId());
        return new DiagnosisRequestResponse(request.requestId(), request.diagnosisId(), request.status(),
                request.failureCode(), request.createdAt(), request.policyOutcome());
    }

    @PostMapping("/api/v1/planning/diagnoses/{requestId}/controller-suggestion")
    @Operation(operationId = "suggestEarlyBunchingMitigation",
            summary = "Record controller mitigation suggestions before AI output is available",
            description = "CONTROLLER only. Records one to three bus-specific suggestions for a detected bunching incident in the active data selection. Identical retries return the original suggestion; a different replacement returns 409. This records controller intent and does not dispatch a bus instruction.")
    @ApiResponse(responseCode = "200", description = "Suggestion stored or identical suggestion replayed")
    @ApiResponse(responseCode = "400", description = "A bus instruction or rationale is invalid")
    @ApiResponse(responseCode = "404", description = "Diagnosis request not found")
    @ApiResponse(responseCode = "409", description = "A different suggestion exists or the request is outside the active scope")
    public EarlyBunchingControllerSuggestionResponse suggest(@PathVariable UUID requestId,
            @Valid @RequestBody EarlyBunchingControllerSuggestionRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return EarlyBunchingControllerSuggestionResponse.from(suggestions.suggest(requestId,
                body.instructions().stream().map(instruction -> instruction.toDomain()).toList(),
                body.controllerRationale(), jwt.getSubject()));
    }

    @GetMapping("/api/v1/planning/diagnoses/controller-suggestions")
    @Operation(operationId = "listEarlyBunchingMitigationSuggestions",
            summary = "List early controller bunching mitigation suggestions",
            description = "Controller/admin only. Returns controller-authored bus actions recorded before AI mitigation was available, limited to the active data selection and ordered newest first.")
    public EarlyBunchingControllerSuggestionPageResponse suggestions(
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size) {
        var items = suggestions.history(page, size).stream().map(EarlyBunchingControllerSuggestionResponse::from).toList();
        long totalItems = suggestions.countHistory();
        return new EarlyBunchingControllerSuggestionPageResponse(items, page, size, totalItems,
                totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size));
    }
}
