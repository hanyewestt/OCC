package com.csd.occ.planning.diagnosis.api;

import com.csd.occ.planning.diagnosis.api.dto.CreateDiagnosisEventSubmission;
import com.csd.occ.planning.diagnosis.api.dto.DiagnosisRequestResponse;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Root-cause diagnosis requests")
public class DiagnosisRequestController {
    private final DiagnosisRequestService requests;
    private final com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService query;

    public DiagnosisRequestController(DiagnosisRequestService requests,
                                     com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService query) {
        this.requests = requests; this.query = query;
    }

    @PostMapping("/internal/v1/planning/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "createDiagnosisRequest", summary = "Queue diagnosis for an immutable producer event",
            description = "The producer submits a versioned event snapshot. Planning enriches it from Ingestion and stores the input before accepting the request.")
    @ApiResponse(responseCode = "202", description = "Diagnosis request accepted or an existing request was returned")
    @ApiResponse(responseCode = "400", description = "Request validation failed, including a missing or invalid idempotency key or event fingerprint",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The idempotency key or event identity has different immutable facts",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Recognized event type does not yet have a fact adapter",
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
        return new DiagnosisRequestResponse(view.requestId(), view.diagnosisId(), view.status(), view.failureCode(), view.createdAt());
    }

    @GetMapping("/api/v1/planning/requests/{requestId}")
    @Operation(operationId = "getDiagnosisRequest", summary = "Get the request lifecycle status")
    @ApiResponse(responseCode = "200", description = "Diagnosis request status")
    @ApiResponse(responseCode = "404", description = "Diagnosis request not found")
    public DiagnosisRequestResponse get(@PathVariable UUID requestId) {
        var request = query.findRequest(requestId);
        return new DiagnosisRequestResponse(request.requestId(), request.diagnosisId(), request.status(), request.failureCode(), request.createdAt());
    }
}
