package com.csd.occ.disruption.bunching.api.error;

import com.csd.occ.disruption.bunching.domain.InvalidConsumerSelectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InvalidConsumerSelectionException.class)
    ProblemDetail invalidConsumerSelection(InvalidConsumerSelectionException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid consumer selection");
        return problem;
    }
}
