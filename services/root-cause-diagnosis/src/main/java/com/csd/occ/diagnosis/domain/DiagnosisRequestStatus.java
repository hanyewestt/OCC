package com.csd.occ.diagnosis.domain;

public enum DiagnosisRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL
}
