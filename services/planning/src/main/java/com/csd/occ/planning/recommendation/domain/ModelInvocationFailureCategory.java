package com.csd.occ.planning.recommendation.domain;

public enum ModelInvocationFailureCategory {
    TIMEOUT,
    AUTHENTICATION,
    RATE_LIMITED,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    PROVIDER_ERROR
}
