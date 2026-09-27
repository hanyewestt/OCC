package com.csd.occ.ingestion.api.error;

import com.csd.occ.ingestion.domain.ReferenceTopologyUnavailableException;
import com.csd.occ.ingestion.domain.DemoBunchingUnavailableException;
import com.csd.occ.ingestion.domain.DemoRecommendationUnavailableException;
import com.csd.occ.ingestion.domain.DemoRunAlreadyActiveException;
import com.csd.occ.ingestion.domain.DemoRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ReferenceTopologyUnavailableException.class)
    ProblemDetail referenceTopologyUnavailable(ReferenceTopologyUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Reference topology unavailable");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception) {
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

    @ExceptionHandler(DemoRecommendationUnavailableException.class)
    ProblemDetail demoRecommendationUnavailable(DemoRecommendationUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Recommendation output unavailable");
        return problem;
    }
}
