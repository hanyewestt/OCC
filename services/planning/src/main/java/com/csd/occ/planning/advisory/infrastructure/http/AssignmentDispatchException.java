package com.csd.occ.planning.advisory.infrastructure.http;

import org.springframework.http.HttpStatusCode;

public final class AssignmentDispatchException extends RuntimeException {
    private final HttpStatusCode status;
    public AssignmentDispatchException(HttpStatusCode status) { this.status = status; }
    public HttpStatusCode status() { return status; }
}
