package com.csd.occ.operations.domain;

public class RouteReferenceUnavailableException extends RuntimeException {
    public RouteReferenceUnavailableException(Throwable cause) {
        super("Ingestion route reference is unavailable", cause);
    }
}
