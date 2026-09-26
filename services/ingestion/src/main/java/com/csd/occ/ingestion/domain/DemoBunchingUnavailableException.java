package com.csd.occ.ingestion.domain;

public final class DemoBunchingUnavailableException extends RuntimeException {
    public DemoBunchingUnavailableException() { super("Bunching control could not be completed"); }
}
