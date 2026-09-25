package com.csd.occ.operations.api.error;

import com.csd.occ.operations.domain.DemoRunConflictException;
import com.csd.occ.operations.domain.DemoRunNotFoundException;
import com.csd.occ.operations.domain.InvalidDemoRouteException;
import com.csd.occ.operations.domain.RouteReferenceUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(DemoRunNotFoundException.class)
    ProblemDetail notFound(DemoRunNotFoundException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Demo run not found");
    }

    @ExceptionHandler({DemoRunConflictException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Demo run state or vehicle label/plate conflicts with an existing record");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid demo run or vehicle input");
    }

    @ExceptionHandler(InvalidDemoRouteException.class)
    ProblemDetail invalidRoute(InvalidDemoRouteException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(RouteReferenceUnavailableException.class)
    ProblemDetail routeUnavailable(RouteReferenceUnavailableException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Route reference is temporarily unavailable");
    }
}
