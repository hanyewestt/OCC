package com.csd.occ.diagnosis.api.error;

import com.csd.occ.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.diagnosis.domain.DiagnosisRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(DiagnosisQueryService.DiagnosisNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Diagnosis Not Found", "The requested diagnosis does not exist.");
    }

    @ExceptionHandler(DiagnosisRequestService.UnsupportedEventTypeException.class)
    public ResponseEntity<ProblemDetail> unsupportedEvent(DiagnosisRequestService.UnsupportedEventTypeException failure) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported Event Type", failure.getMessage());
    }

    @ExceptionHandler(DiagnosisRequestService.IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> idempotencyConflict() {
        return problem(HttpStatus.CONFLICT, "Idempotency Conflict", "The idempotency key is already assigned to another event.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException failure) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", failure.getMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}
