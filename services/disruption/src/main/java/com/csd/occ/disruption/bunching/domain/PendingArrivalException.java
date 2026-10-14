package com.csd.occ.disruption.bunching.domain;

/** An incomplete ingestion batch must remain eligible for a later retry. */
public class PendingArrivalException extends RuntimeException {
    public PendingArrivalException() {
        super("INCOMPLETE_NORMALIZED_SNAPSHOT");
    }
}
