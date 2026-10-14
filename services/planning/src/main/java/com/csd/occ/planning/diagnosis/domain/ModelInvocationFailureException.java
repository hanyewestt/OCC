package com.csd.occ.planning.diagnosis.domain;

public class ModelInvocationFailureException extends RuntimeException {
    private final boolean retryable;
    private final ModelInvocationDetails invocationDetails;
    public ModelInvocationFailureException(boolean retryable, ModelInvocationDetails invocationDetails, Throwable cause) {
        super("MODEL_PROVIDER_FAILURE", cause);
        this.retryable = retryable;
        this.invocationDetails = invocationDetails;
    }
    public boolean isRetryable() { return retryable; }
    public ModelInvocationDetails invocationDetails() { return invocationDetails; }
}
