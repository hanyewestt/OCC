package com.csd.occ.planning.api.error;

import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisFailureException;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.recommendation.domain.IdempotencyConflictException;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionService;
import com.csd.occ.planning.advisory.domain.AdvisoryProposalRepository;
import com.csd.occ.planning.advisory.domain.IssuedInstructionRepository;
import com.csd.occ.planning.advisory.domain.InstructionType;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String MOVEMENT_INDEX = "uq_issued_instruction_movement";
    private final AdvisoryProposalRepository proposals;
    private final IssuedInstructionRepository instructions;

    public GlobalExceptionHandler(AdvisoryProposalRepository proposals, IssuedInstructionRepository instructions) {
        this.proposals = proposals;
        this.instructions = instructions;
    }

    @ExceptionHandler(com.csd.occ.planning.advisory.domain.AdvisoryConflictException.class)
    ProblemDetail advisoryConflict(com.csd.occ.planning.advisory.domain.AdvisoryConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Advisory conflict", exception.getMessage(), "urn:occ:problem:advisory-conflict", request);
    }
    @ExceptionHandler(com.csd.occ.planning.advisory.domain.AdvisoryNotFoundException.class)
    ProblemDetail advisoryNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Advisory not found", "The requested advisory is not available.", "urn:occ:problem:advisory-not-found", request);
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail concurrentAdvisoryConflict(org.springframework.dao.DataIntegrityViolationException exception,
            HttpServletRequest request) {
        if (violatesMovementIndex(exception)) {
            var proposalId = advisoryDecisionProposalId(request.getRequestURI());
            var proposal = proposalId.flatMap(proposals::findById);
            if (proposal.isPresent()) {
                var winner = instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(
                                proposal.get().demoRunId(), proposal.get().busId(), List.of("ISSUED", "ACKNOWLEDGED"))
                        .stream().filter(instruction -> instruction.type() != InstructionType.BE_CAUTIOUS)
                        .findFirst();
                if (winner.isPresent())
                    return problem(HttpStatus.CONFLICT, "Advisory conflict",
                            "Active movement instruction " + winner.get().id() + " must be explicitly replaced",
                            "urn:occ:problem:advisory-conflict", request);
            }
        }
        return problem(HttpStatus.CONFLICT, "Concurrent update conflict", "Another decision changed this resource. Refresh before retrying.", "urn:occ:problem:concurrent-update", request);
    }

    private boolean violatesMovementIndex(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause())
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && MOVEMENT_INDEX.equals(violation.getConstraintName())) return true;
        return false;
    }

    private Optional<UUID> advisoryDecisionProposalId(String requestUri) {
        String[] segments = requestUri.split("/");
        if (segments.length < 3 || !"decisions".equals(segments[segments.length - 1])
                || !"advisories".equals(segments[segments.length - 3])) return Optional.empty();
        try { return Optional.of(UUID.fromString(segments[segments.length - 2])); }
        catch (IllegalArgumentException malformedId) { return Optional.empty(); }
    }
    @ExceptionHandler(com.csd.occ.planning.diagnosis.domain.PlanningScopePort.UnavailableException.class)
    ProblemDetail handleScopeUnavailable(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Data selection unavailable",
                "The shared data selection cannot be verified. Retry when Disruption is available.",
                "urn:occ:problem:data-selection-unavailable", request);
    }

    @ExceptionHandler(DiagnosisQueryService.DiagnosisNotFoundException.class)
    ProblemDetail handleNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "The requested planning resource does not exist.",
                "urn:occ:problem:not-found", request);
    }

    @ExceptionHandler(RecommendationDecisionService.RecommendationNotFoundException.class)
    ProblemDetail handleRecommendationNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Recommendation not found",
                "The requested recommendation does not exist.", "urn:occ:problem:recommendation-not-found", request);
    }

    @ExceptionHandler(RecommendationDecisionService.RecommendationDecisionConflictException.class)
    ProblemDetail handleRecommendationDecisionConflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Recommendation already decided",
                "This recommendation already has a different controller decision.",
                "urn:occ:problem:recommendation-decision-conflict", request);
    }

    @ExceptionHandler(RecommendationDecisionService.RecommendationNotActionableException.class)
    ProblemDetail handleRecommendationNotActionable(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Recommendation is review only",
                "Only actionable simulation recommendations can receive a controller decision.",
                "urn:occ:problem:recommendation-not-actionable", request);
    }

    @ExceptionHandler(DiagnosisRequestService.UnsupportedEventTypeException.class)
    ProblemDetail handleUnsupportedEvent(DiagnosisRequestService.UnsupportedEventTypeException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported event type", exception.getMessage(),
                "urn:occ:problem:unsupported-event-type", request);
    }

    @ExceptionHandler(DiagnosisFailureException.class)
    ProblemDetail handleDiagnosisFailure(DiagnosisFailureException exception, HttpServletRequest request) {
        HttpStatus status = exception.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        String title = exception.isRetryable() ? "Planning context unavailable" : "Planning input rejected";
        String detail = exception.isRetryable()
                ? "Planning could not capture the event evidence snapshot; retry the same request."
                : "Planning could not capture a valid event evidence snapshot.";
        ProblemDetail problem = problem(status, title, detail, "urn:occ:problem:planning-context", request);
        problem.setProperty("failureCode", exception.failureCode());
        return problem;
    }

    @ExceptionHandler({DiagnosisRequestService.IdempotencyConflictException.class, IdempotencyConflictException.class})
    ProblemDetail handleIdempotencyConflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict",
                "The idempotency key is already assigned to a different request.",
                "urn:occ:problem:idempotency-conflict", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed",
                "One or more request fields are invalid.", "urn:occ:problem:request-validation", request);
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class})
    ProblemDetail handleMalformedRequest(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request is malformed or contains an unsupported value.",
                "urn:occ:problem:invalid-request", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId != null && !correlationId.isBlank()) problem.setProperty("correlationId", correlationId);
        return problem;
    }

    private record ValidationError(String field, String message) {}
}
