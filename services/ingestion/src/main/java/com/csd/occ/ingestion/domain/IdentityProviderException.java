package com.csd.occ.ingestion.domain;

/** Safe categories only: provider responses, credentials and causes must never escape the adapter. */
public class IdentityProviderException extends RuntimeException {
    public enum Reason { UNAVAILABLE, DUPLICATE_EMAIL, PASSWORD_POLICY }
    private final Reason reason;

    public IdentityProviderException(Reason reason) {
        super(switch (reason) {
            case UNAVAILABLE -> "Identity provider is unavailable";
            case DUPLICATE_EMAIL -> "An account with this email already exists";
            case PASSWORD_POLICY -> "Password does not meet the identity provider policy";
        });
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
