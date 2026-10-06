package com.csd.occ.diagnosis.domain;

public final class EvidenceLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EvidenceLimitExceededException(String field) {
        super(field + " exceeds its configured evidence limit");
    }
}
