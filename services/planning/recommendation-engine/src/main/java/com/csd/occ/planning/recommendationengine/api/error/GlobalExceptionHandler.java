package com.csd.occ.planning.recommendationengine.api.error;

import com.csd.occ.planning.recommendationengine.domain.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more request fields are invalid.",
                "urn:occ:problem:request-validation",
                request);
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                "The request is malformed or contains an unsupported value.",
                "urn:occ:problem:invalid-request",
                request);
    }

    @ExceptionHandler(NoActionableRecommendationException.class)
    ProblemDetail handleNoActionableRecommendation(
            NoActionableRecommendationException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "No actionable recommendation",
                exception.getMessage(),
                "urn:occ:problem:no-actionable-recommendation",
                request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "Idempotency conflict",
                exception.getMessage(),
                "urn:occ:problem:idempotency-conflict",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    private record ValidationError(String field, String message) {
    }
}
