package com.csd.occ.ingestion.api.error;

import com.csd.occ.ingestion.domain.ReferenceTopologyUnavailableException;
import com.csd.occ.ingestion.domain.DemoBunchingUnavailableException;
import com.csd.occ.ingestion.domain.DemoRecommendationUnavailableException;
import com.csd.occ.ingestion.domain.DemoRunAlreadyActiveException;
import com.csd.occ.ingestion.domain.DemoRunNotFoundException;
import com.csd.occ.ingestion.domain.PacingSubscriberLimitException;
import com.csd.occ.ingestion.domain.PacingUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.csd.occ.ingestion.domain.DirectoryAccessDeniedException.class)
    ProblemDetail directoryAccessDenied(com.csd.occ.ingestion.domain.DirectoryAccessDeniedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(com.csd.occ.ingestion.domain.DirectoryUserNotFoundException.class)
    ProblemDetail directoryUserNotFound(com.csd.occ.ingestion.domain.DirectoryUserNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(com.csd.occ.ingestion.domain.IdentityProviderException.class)
    ProblemDetail identityProvider(com.csd.occ.ingestion.domain.IdentityProviderException exception) {
        HttpStatus status = switch (exception.reason()) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DUPLICATE_EMAIL -> HttpStatus.CONFLICT;
            case PASSWORD_POLICY -> HttpStatus.BAD_REQUEST;
        };
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }

    @ExceptionHandler(ReferenceTopologyUnavailableException.class)
    ProblemDetail referenceTopologyUnavailable(ReferenceTopologyUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Reference topology unavailable");
        return problem;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.validation.BindException.class})
    ProblemDetail invalidRequest(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request values are invalid");
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidSimulationEvidence(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(DemoRunAlreadyActiveException.class)
    ProblemDetail demoRunAlreadyActive(DemoRunAlreadyActiveException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Demo run already active");
        problem.setProperty("activeRunId", exception.activeRunId());
        return problem;
    }

    @ExceptionHandler(DemoRunNotFoundException.class)
    ProblemDetail demoRunNotFound(DemoRunNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Demo run not found");
        return problem;
    }

    @ExceptionHandler(DemoBunchingUnavailableException.class)
    ProblemDetail demoBunchingUnavailable(DemoBunchingUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Bunching control unavailable");
        return problem;
    }

    @ExceptionHandler(PacingUnavailableException.class)
    ProblemDetail pacingUnavailable(PacingUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Pacing metrics unavailable");
        return problem;
    }

    @ExceptionHandler(PacingSubscriberLimitException.class)
    ProblemDetail pacingSubscriberLimit(PacingSubscriberLimitException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Pacing stream at capacity");
        return problem;
    }

    @ExceptionHandler(DemoRecommendationUnavailableException.class)
    ProblemDetail demoRecommendationUnavailable(DemoRecommendationUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Recommendation output unavailable");
        return problem;
    }
}
