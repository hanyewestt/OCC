package com.csd.occ.ingestion.domain;

public class PacingSubscriberLimitException extends RuntimeException {
    public PacingSubscriberLimitException(int limit) {
        super("The pacing stream already has the maximum of " + limit + " subscribers; retry later");
    }
}
