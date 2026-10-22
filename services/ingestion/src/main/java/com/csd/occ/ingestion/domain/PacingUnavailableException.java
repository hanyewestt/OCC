package com.csd.occ.ingestion.domain;

public class PacingUnavailableException extends RuntimeException {
    public PacingUnavailableException() {
        super("Pacing metrics have not been calculated yet; retry after the next calculation cycle");
    }
}
