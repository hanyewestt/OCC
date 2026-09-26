package com.csd.occ.planning.recommendationengine.domain;

public enum ModelInvocationFailureCategory {
    TIMEOUT,
    AUTHENTICATION,
    RATE_LIMITED,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    PROVIDER_ERROR
}
