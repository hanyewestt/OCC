package com.csd.occ.ingestion.domain;

public class ReferenceTopologyUnavailableException extends RuntimeException {
    public ReferenceTopologyUnavailableException() {
        super("No active reference topology is available");
    }
}
