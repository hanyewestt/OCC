package com.csd.occ.disruption.bunching.domain;

/** Contains a safe reason code, never a provider payload or database error. */
public class InvalidArrivalException extends RuntimeException {
    public InvalidArrivalException(String reason) {
        super(reason);
    }
}
