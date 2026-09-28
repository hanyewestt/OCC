package com.csd.occ.bunching.domain;

/** Contains a safe reason code, never a provider payload or database error. */
public class InvalidArrivalException extends RuntimeException {
    public InvalidArrivalException(String reason) {
        super(reason);
    }
}
