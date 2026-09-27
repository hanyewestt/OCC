package com.csd.occ.diagnosis.domain;

public class DiagnosisFailureException extends RuntimeException {
    private final boolean retryable;
    private final String failureCode;

    public DiagnosisFailureException(String failureCode, boolean retryable) {
        super(failureCode);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }
    public DiagnosisFailureException(String failureCode, boolean retryable, Throwable cause) {
        super(failureCode, cause);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }
    public boolean isRetryable() { return retryable; }
    public String failureCode() { return failureCode; }
}
